package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** 独立的短 SQLite 事务。冻结 diff 与成功状态在同一次提交中生效。 */
public final class SqliteAgentStore implements AgentStore {
    private final DataSource source;
    private final ObjectMapper json = new ObjectMapper();

    public SqliteAgentStore(DataSource source) {
        this.source = source;
    }

    @FunctionalInterface
    interface Operation<T> {
        T apply(Connection connection) throws Exception;
    }

    <T> T transaction(Operation<T> operation) {
        try (Connection connection = source.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("BEGIN IMMEDIATE");
            }
            try {
                T result = operation.apply(connection);
                try (var statement = connection.createStatement()) {
                    statement.execute("COMMIT");
                }
                return result;
            } catch (Exception failure) {
                try (var statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                if (failure instanceof AgentException classified) {
                    throw classified;
                }
                throw new AgentException("PERSISTENCE_FAILURE", "Transaction outcome requires durable readback", failure);
            }
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Agent database is unavailable", failure);
        }
    }

    static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private static void requireOne(int count) {
        if (count != 1) {
            throw new AgentException("RUN_CONFLICT", "Run state, authorization or trusted baseline changed");
        }
    }

    @Override
    public void reserve(AgentProtocol.Run run) {
        transaction(connection -> {
            try {
                requireOne(update(connection, """
                        INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,started_at,tokens)
                        SELECT ?,p.id,s.id,w.id,?,'CREATED',?,0 FROM evolution_plan p
                        JOIN evolution_step s ON s.plan_id=p.id JOIN working_copy w ON w.id=p.working_copy_id
                        JOIN software_asset a ON a.id=p.base_asset_id
                        WHERE p.id=? AND s.id=? AND w.id=? AND p.status='ACTIVE' AND s.status='READY'
                        AND w.status IN ('READY','EVOLVING') AND w.current_revision=? AND w.last_verified_revision=?
                        AND a.read_permission=1 AND a.usage_authorization='ALLOWED'
                        AND NOT EXISTS (SELECT 1 FROM evolution_step other WHERE other.plan_id=p.id
                            AND other.status IN ('EXECUTING','VERIFYING','VERIFICATION_FAILED','ROLLING_BACK','RECOVERY_REQUIRED'))
                        AND NOT EXISTS (SELECT 1 FROM evolution_step preceding WHERE preceding.plan_id=p.id
                            AND preceding.position<s.position AND preceding.status<>'SUCCEEDED')
                        AND NOT EXISTS (SELECT 1 FROM agent_run frozen WHERE frozen.step_id=s.id
                            AND frozen.baseline=? AND frozen.state='SUCCEEDED')
                        """, run.id(), run.baseline(), run.startedAt().toString(), run.planId(), run.stepId(),
                        run.workingCopyId(), run.baseline(), run.baseline(), run.baseline()));
            } catch (SQLException failure) {
                if (failure.getErrorCode() == 19) {
                    throw new AgentException("RUN_CONFLICT", "WorkingCopy already has an active Run or frozen Candidate");
                }
                throw failure;
            }
            addEvent(connection, run.id(), "CREATED", "Run reserved from confirmed Step");
            return null;
        });
    }

    private static AgentProtocol.Run readRun(ResultSet row) throws SQLException {
        String ended = row.getString("ended_at");
        Number tokens = (Number) row.getObject("tokens");
        return new AgentProtocol.Run(row.getString("id"), row.getString("plan_id"), row.getString("step_id"),
                row.getString("working_copy_id"), row.getString("baseline"), AgentRunState.valueOf(row.getString("state")),
                Instant.parse(row.getString("started_at")), ended == null ? null : Instant.parse(ended),
                row.getString("reason"), row.getString("candidate_id"), row.getInt("cancel_requested") != 0,
                row.getInt("retained") != 0, row.getInt("model_calls"), row.getInt("tool_calls"),
                tokens == null ? null : tokens.longValue());
    }

    @Override
    public AgentProtocol.Run get(String id) {
        try (Connection connection = source.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM agent_run WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AgentException("NOT_FOUND", "Agent Run not found");
                }
                return readRun(rows);
            }
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read Agent Run", failure);
        }
    }

    @Override
    public List<AgentProtocol.Run> abandoned() {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT * FROM agent_run WHERE occupied=1")) {
            List<AgentProtocol.Run> result = new ArrayList<>();
            while (rows.next()) {
                result.add(readRun(rows));
            }
            return result;
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot reconcile Agent Runs", failure);
        }
    }

    @Override
    public List<AgentProtocol.Run> runs(String planId, String stepId, String before, int limit) {
        if (limit < 1 || limit > 100) {
            throw new AgentException("INVALID_REQUEST", "Invalid Run history page size");
        }
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement("""
                SELECT * FROM agent_run WHERE plan_id=? AND step_id=?
                AND (? IS NULL OR rowid < (SELECT rowid FROM agent_run WHERE id=?)) ORDER BY rowid DESC LIMIT ?
                """)) {
            bind(statement, planId, stepId, before, before, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<AgentProtocol.Run> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(readRun(rows));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read Run history", failure);
        }
    }

    @Override
    public void advance(String id, AgentRunState expected, AgentRunState next, String reason) {
        if (!expected.canAdvanceTo(next) || next == AgentRunState.SUCCEEDED) {
            throw new AgentException("RUN_CONFLICT", "Illegal Run transition; success requires atomic Candidate freeze");
        }
        transaction(connection -> {
            // 在允许 Agent 写入前提交保留意图；失败终态不得覆盖它。NO_CHANGES 证明没有候选材料。
            requireOne(update(connection, """
                    UPDATE agent_run SET state=?,reason=?,ended_at=?,retained=CASE
                        WHEN ? IN ('RUNNING','FINALIZING') THEN 1
                        WHEN state='FINALIZING' AND ?='NO_CHANGES' THEN 0
                        ELSE retained END
                    WHERE id=? AND state=?
                    """, next.name(), reason, next.terminal() ? Instant.now().toString() : null,
                    next.name(), reason, id, expected.name()));
            addEvent(connection, id, next.name(), reason == null ? next.name() : reason);
            return null;
        });
    }

    static void addEvent(Connection connection, String id, String kind, String summary) throws SQLException {
        if (summary.length() > 24000 || kind.length() > 80) {
            throw new AgentException("EVENT_LIMIT", "Agent event exceeds its budget");
        }
        update(connection, "INSERT INTO agent_event(run_id,kind,summary,at) VALUES(?,?,?,?)",
                id, kind, summary, Instant.now().toString());
    }

    @Override
    public void event(String id, String kind, String summary) {
        transaction(connection -> {
            addEvent(connection, id, kind, summary);
            return null;
        });
    }

    @Override
    public List<AgentProtocol.Event> events(String id, long after, int limit) {
        get(id);
        if (after < 0 || limit < 1 || limit > 200) {
            throw new AgentException("INVALID_REQUEST", "Invalid event pagination");
        }
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_event WHERE run_id=? AND sequence>? ORDER BY sequence LIMIT ?")) {
            bind(statement, id, after, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<AgentProtocol.Event> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new AgentProtocol.Event(rows.getLong("sequence"), id, rows.getString("kind"),
                            rows.getString("summary"), Instant.parse(rows.getString("at"))));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read Agent events", failure);
        }
    }

    @Override
    public void usage(String id, int modelCalls, int toolCalls, Long tokens) {
        transaction(connection -> {
            requireOne(update(connection, "UPDATE agent_run SET model_calls=?,tool_calls=?,tokens=? WHERE id=?",
                    modelCalls, toolCalls, tokens, id));
            return null;
        });
    }

    @Override
    public void requestCancel(String id) {
        transaction(connection -> {
            update(connection, "UPDATE agent_run SET cancel_requested=1 WHERE id=? AND state NOT IN "
                    + "('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')", id);
            return null;
        });
    }

    @Override
    public void freeze(Candidate candidate) {
        transaction(connection -> {
            requireOne(update(connection, """
                    UPDATE agent_run SET state='SUCCEEDED',reason='CANDIDATE_FROZEN',candidate_id=?,ended_at=?,retained=0
                    WHERE id=? AND baseline=? AND state='FINALIZING' AND cancel_requested=0 AND occupied=1
                    AND EXISTS (SELECT 1 FROM evolution_plan p JOIN evolution_step s ON s.plan_id=p.id
                        JOIN working_copy w ON w.id=p.working_copy_id JOIN software_asset a ON a.id=p.base_asset_id
                        WHERE p.id=agent_run.plan_id AND s.id=agent_run.step_id AND s.status='READY'
                        AND p.status='ACTIVE' AND w.id=agent_run.working_copy_id
                        AND w.current_revision=agent_run.baseline AND w.last_verified_revision=agent_run.baseline
                        AND a.read_permission=1 AND a.usage_authorization='ALLOWED')
                    """, candidate.id(), Instant.now().toString(), candidate.runId(), candidate.baseline()));
            update(connection, "INSERT INTO agent_candidate VALUES(?,?,?,?,?,?,?,?,?)", candidate.id(),
                    candidate.runId(), candidate.baseline(), candidate.expectedTree(),
                    candidate.diff().getBytes(StandardCharsets.UTF_8), candidate.sha256(),
                    json.writeValueAsString(candidate.changedPaths()), candidate.testEvidence(),
                    candidate.createdAt().toString());
            addEvent(connection, candidate.runId(), "SUCCEEDED", "Candidate durably frozen; official WorkingCopy unchanged");
            return null;
        });
    }

    @Override
    public Candidate candidate(String id) {
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_candidate WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new AgentException("NOT_FOUND", "Candidate not found");
                }
                return new Candidate(row.getString("id"), row.getString("run_id"), row.getString("baseline"),
                        row.getString("expected_tree"), new String(row.getBytes("diff"), StandardCharsets.UTF_8),
                        row.getString("sha256"), json.readValue(row.getString("changed_paths"),
                                new TypeReference<List<String>>() { }), row.getString("test_evidence"),
                        Instant.parse(row.getString("created_at")));
            }
        } catch (AgentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AgentException("CANDIDATE_INTEGRITY", "Cannot load and verify frozen Candidate", failure);
        }
    }

    @Override
    public void release(String id, boolean retainMaterial) {
        transaction(connection -> {
            requireOne(update(connection, "UPDATE agent_run SET occupied=0,retained=MAX(retained,?) WHERE id=? "
                    + "AND state IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')", retainMaterial ? 1 : 0, id));
            // 保留标志只能升不能降；事件描述持久事实，不能照抄本次调用参数。
            try (PreparedStatement statement = connection.prepareStatement("SELECT retained FROM agent_run WHERE id=?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    addEvent(connection, id, "RESOURCES_RELEASED",
                            row.getInt(1) != 0 ? "STOPPED_MATERIAL_RETAINED" : "CLEANED");
                }
            }
            return null;
        });
    }
}
