package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.agent.DeliveryStore;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import static com.ayywl.delveforge.infrastructure.agent.SqliteAgentStore.addEvent;
import static com.ayywl.delveforge.infrastructure.agent.SqliteAgentStore.bind;
import static com.ayywl.delveforge.infrastructure.agent.SqliteAgentStore.update;

/** 日志、预约、接受与事件均使用短 BEGIN IMMEDIATE 事务；不在事务内执行 Git。 */
public final class SqliteDeliveryStore implements DeliveryStore {
    private static final String AUTHORIZED = """
            SELECT 1 FROM evolution_plan p JOIN evolution_step s ON s.plan_id=p.id
            JOIN working_copy w ON w.id=p.working_copy_id JOIN software_asset a ON a.id=p.base_asset_id
            JOIN product_direction pd ON pd.id=p.product_direction_id
            WHERE p.id=? AND s.id=? AND w.id=? AND p.status='ACTIVE' AND s.status='READY'
            AND w.status IN ('READY','EVOLVING') AND w.current_revision=? AND w.last_verified_revision=?
            AND w.location=? AND a.location=? AND a.id=w.source_asset_id
            AND a.type='GIT_REPOSITORY' AND a.read_permission=1 AND a.usage_authorization='ALLOWED'
            AND a.license_info IS NOT NULL AND trim(a.license_info)<>'' AND pd.status='SELECTED'
            AND p.flow_id IS NOT NULL AND p.flow_id=pd.flow_id
            AND NOT EXISTS (SELECT 1 FROM evolution_step preceding WHERE preceding.plan_id=p.id
                AND preceding.position<s.position AND preceding.status<>'SUCCEEDED')
            AND NOT EXISTS (SELECT 1 FROM evolution_step other WHERE other.plan_id=p.id
                AND other.status IN ('EXECUTING','VERIFYING','VERIFICATION_FAILED','ROLLING_BACK','RECOVERY_REQUIRED'))
            """;

    private final DataSource source;
    private final SqliteAgentStore transactions;
    private final ExecutionPreparationState eligibility;

    public SqliteDeliveryStore(DataSource source, ExecutionPreparationState eligibility) {
        this.source = source;
        this.transactions = new SqliteAgentStore(source);
        this.eligibility = eligibility;
    }

    @Override
    public Delivery reserve(Candidate candidate, AgentProtocol.Run run, String location, String originalLocation) {
        return transactions.transaction(connection -> {
            Delivery existing = read(connection, candidate.id());
            if (existing != null) {
                return existing;
            }
            Delivery delivery = new Delivery(candidate.id(), run.id(), run.planId(), run.stepId(), run.workingCopyId(),
                    candidate.baseline(), candidate.expectedTree(), location, originalLocation,
                    Delivery.State.INTENT, null, null, Instant.now());
            requireAuthorized(connection, delivery);
            try (var statement = connection.prepareStatement("""
                    SELECT 1 FROM agent_run r JOIN agent_candidate c ON c.id=r.candidate_id
                    WHERE r.id=? AND c.id=? AND r.state='SUCCEEDED' AND r.occupied=0
                    AND r.plan_id=? AND r.step_id=? AND r.working_copy_id=? AND r.baseline=?
                    AND c.baseline=r.baseline AND c.expected_tree=? AND c.sha256=?
                    """)) {
                bind(statement, run.id(), candidate.id(), run.planId(), run.stepId(), run.workingCopyId(),
                        candidate.baseline(), candidate.expectedTree(), candidate.sha256());
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next());
                }
            }
            update(connection, """
                    INSERT INTO candidate_delivery(candidate_id,run_id,plan_id,step_id,working_copy_id,baseline,
                        expected_tree,location,original_location,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,'INTENT',?)
                    """, delivery.candidateId(), delivery.runId(), delivery.planId(), delivery.stepId(),
                    delivery.workingCopyId(), delivery.baseline(), delivery.expectedTree(), delivery.location(),
                    delivery.originalLocation(), delivery.createdAt().toString());
            addEvent(connection, delivery.runId(), "DELIVERY_INTENT", "Frozen Candidate reserved for official Delivery");
            return delivery;
        });
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AgentException("DELIVERY_CONFLICT", "Delivery authorization or checkpoint changed");
        }
    }

    private static Delivery read(Connection connection, String candidateId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM candidate_delivery WHERE candidate_id=?")) {
            bind(statement, candidateId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    private static Delivery read(ResultSet row) throws SQLException {
        return new Delivery(row.getString("candidate_id"), row.getString("run_id"), row.getString("plan_id"),
                row.getString("step_id"), row.getString("working_copy_id"), row.getString("baseline"),
                row.getString("expected_tree"), row.getString("location"), row.getString("original_location"),
                Delivery.State.valueOf(row.getString("state")), row.getString("revision"), row.getString("reason"),
                Instant.parse(row.getString("created_at")));
    }

    @Override
    public Delivery get(String candidateId) {
        try (Connection connection = source.getConnection()) {
            return read(connection, candidateId);
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read durable Delivery", failure);
        }
    }

    @Override
    public List<Delivery> unfinished() {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT * FROM candidate_delivery WHERE occupied=1 AND state<>'RECOVERY_REQUIRED'
                        ORDER BY created_at LIMIT 200
                        """)) {
            List<Delivery> result = new ArrayList<>();
            while (rows.next()) {
                result.add(read(rows));
            }
            return result;
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read unfinished Deliveries", failure);
        }
    }

    @Override
    public List<String> pendingCandidates() {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT c.id FROM agent_candidate c JOIN agent_run r ON r.id=c.run_id
                        JOIN evolution_step s ON s.id=r.step_id JOIN working_copy w ON w.id=r.working_copy_id
                        WHERE r.state='SUCCEEDED' AND r.occupied=0 AND s.status='READY'
                        AND w.last_verified_revision=c.baseline
                        AND NOT EXISTS (SELECT 1 FROM candidate_delivery d WHERE d.candidate_id=c.id)
                        ORDER BY c.created_at LIMIT 200
                        """)) {
            List<String> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read frozen Candidates awaiting Delivery", failure);
        }
    }

    @Override
    public void requireAuthorized(Delivery delivery) {
        var loaded = eligibility.loadDeliveryMetadata(new EvolutionPlanId(delivery.planId()),
                new EvolutionStepId(delivery.stepId()));
        require(loaded.copy().id().value().equals(delivery.workingCopyId())
                && loaded.copy().lastVerifiedRevision().equals(delivery.baseline())
                && loaded.copy().location().equals(delivery.location())
                && loaded.asset().location().equals(delivery.originalLocation()));
        try (Connection connection = source.getConnection()) {
            requireAuthorized(connection, delivery);
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot revalidate Delivery authorization", failure);
        }
    }

    private static void requireAuthorized(Connection connection, Delivery delivery) throws SQLException {
        try (var statement = connection.prepareStatement(AUTHORIZED)) {
            bind(statement, delivery.planId(), delivery.stepId(), delivery.workingCopyId(), delivery.baseline(),
                    delivery.baseline(), delivery.location(), delivery.originalLocation());
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
            }
        }
    }

    @Override
    public Delivery advance(Delivery expected, Delivery.State next, String revision, String reason) {
        boolean terminalBlock = next == Delivery.State.DELIVERY_BLOCKED || next == Delivery.State.RECOVERY_REQUIRED;
        boolean permitted = next == expected.state() || terminalBlock || switch (expected.state()) {
            case INTENT, DELIVERY_BLOCKED, RECOVERY_REQUIRED -> next == Delivery.State.APPLIED
                    || next == Delivery.State.COMMIT_READY || next == Delivery.State.PUBLISHED;
            case APPLIED -> next == Delivery.State.COMMIT_READY;
            case COMMIT_READY -> next == Delivery.State.PUBLISHED;
            case PUBLISHED, COMPLETED -> false;
        };
        require(permitted && next != Delivery.State.COMPLETED && expected.state() != Delivery.State.COMPLETED
                && (revision == null || revision.matches("[0-9a-f]{40}"))
                && (expected.revision() == null || expected.revision().equals(revision)));
        return transactions.transaction(connection -> {
            require(expected.equals(read(connection, expected.candidateId())));
            require(expected.state() != Delivery.State.COMPLETED);
            require(update(connection, """
                    UPDATE candidate_delivery SET state=?,revision=?,reason=? WHERE candidate_id=? AND state=?
                    """, next.name(), revision, reason, expected.candidateId(), expected.state().name()) == 1);
            addEvent(connection, expected.runId(), "DELIVERY_" + next.name(), reason == null ? next.name() : reason);
            return read(connection, expected.candidateId());
        });
    }

    @Override
    public void accept(Delivery delivery) {
        // 在隔离领域候选上验证正式接受操作；SQL 事务内再次核对相同基线及授权。
        var loaded = eligibility.loadDeliveryMetadata(new EvolutionPlanId(delivery.planId()),
                new EvolutionStepId(delivery.stepId()));
        EvolutionPlan plan = loaded.plan().copy();
        WorkingCopy copy = loaded.copy().copy();
        plan.acceptDelivery(new EvolutionStepId(delivery.stepId()), copy, delivery.baseline(), delivery.revision());
        transactions.transaction(connection -> {
            require(delivery.equals(read(connection, delivery.candidateId()))
                    && delivery.state() == Delivery.State.PUBLISHED && delivery.revision() != null);
            requireAuthorized(connection, delivery);
            // 同事务内先解除预约，才能更新受触发器保护的 WorkingCopy；回滚也恢复预约。
            require(update(connection, """
                    UPDATE candidate_delivery SET state='COMPLETED',occupied=0,reason=NULL
                    WHERE candidate_id=? AND state='PUBLISHED' AND revision=?
                    """, delivery.candidateId(), delivery.revision()) == 1);
            require(update(connection, """
                    UPDATE evolution_step SET status='SUCCEEDED',baseline_revision=?,delivery_revision=?
                    WHERE id=? AND plan_id=? AND status='READY'
                    """, delivery.baseline(), plan.step(new EvolutionStepId(delivery.stepId())).deliveryRevision(),
                    delivery.stepId(), delivery.planId()) == 1);
            require(update(connection, """
                    UPDATE working_copy SET current_revision=?,last_verified_revision=?,status='EVOLVING'
                    WHERE id=? AND current_revision=? AND last_verified_revision=? AND location=?
                    """, copy.currentRevision(), copy.lastVerifiedRevision(), delivery.workingCopyId(),
                    delivery.baseline(), delivery.baseline(), delivery.location()) == 1);
            addEvent(connection, delivery.runId(), "DELIVERY_COMPLETED", "Accepted official Git commit " + delivery.revision());
            return null;
        });
    }
}
