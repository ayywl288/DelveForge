package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteAgentStoreTest {
    @TempDir
    Path temporary;
    SQLiteDataSource source;
    SqliteAgentStore store;

    @BeforeEach
    void setup() throws Exception {
        source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temporary.resolve("agent.db"));
        Flyway.configure().dataSource(source).load().migrate();
        store = new SqliteAgentStore(source);
        sql("INSERT INTO software_asset VALUES('asset','GIT_REPOSITORY','USER_SPECIFIED','original',1,'MIT','ALLOWED',0)");
        sql("INSERT INTO working_copy VALUES('copy','asset','" + "a".repeat(40) + "','managed','"
                + "a".repeat(40) + "','" + "a".repeat(40) + "','READY')");
        sql("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,working_copy_id,"
                + "current_summary,target_problem,target_product,target_differentiation,status) VALUES"
                + "('plan','direction','asset','profile','copy','current','problem','target','difference','ACTIVE')");
        sql("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('step','plan',0,'goal','scope','READY')");
    }

    void sql(String sql) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    AgentProtocol.Run run() {
        return new AgentProtocol.Run(UUID.randomUUID().toString(), "plan", "step", "copy", "a".repeat(40),
                AgentRunState.CREATED, Instant.now(), null, null, null, false, false, 0, 0, null);
    }

    void finalizing(String id) {
        store.advance(id, AgentRunState.CREATED, AgentRunState.PREPARING, null);
        store.advance(id, AgentRunState.PREPARING, AgentRunState.RUNNING, null);
        store.advance(id, AgentRunState.RUNNING, AgentRunState.FINALIZING, null);
    }

    Candidate candidate(String run) {
        return new Candidate(UUID.randomUUID().toString(), run, "a".repeat(40), "b".repeat(40), "complete diff\n",
                Candidate.digest("complete diff\n"), List.of("src/New.java"), "{\"status\":\"NOT_TESTED\"}", Instant.now());
    }

    @Test
    void legacyAndVersionedToolSummariesSurviveReloadWithoutRewritingOrLosingCursorOrder() {
        var run = run();
        store.reserve(run);
        List<String> summaries = List.of("tool=read_file; id=old-call",
                "{\"tool\":\"read_file\",\"ok\":true,\"classification\":\"RESULT\"}",
                "{\"metadataVersion\":1,\"toolCallId\":\"12345678-1234-4567-8123-123456789abc\","
                        + "\"tool\":\"read_file\",\"path\":\"src/A.java\"}");
        for (int index = 0; index < summaries.size(); index++) {
            store.event(run.id(), index == 1 ? "TOOL_FINISHED" : "TOOL_STARTED", summaries.get(index));
        }
        SqliteAgentStore restarted = new SqliteAgentStore(source);
        var events = restarted.events(run.id(), 0, 100);
        assertEquals(summaries, events.subList(1, events.size()).stream().map(AgentProtocol.Event::summary).toList());
        assertEquals(events.subList(2, events.size()), restarted.events(run.id(), events.get(1).sequence(), 100));
        assertEquals(run.id(), restarted.get(run.id()).id());
        assertEquals(run.baseline(), restarted.get(run.id()).baseline());
    }

    @Test
    void finalizationRetentionSurvivesTerminalCommitAndCannotBeClearedByRelease() throws Exception {
        var run = run();
        store.reserve(run);
        finalizing(run.id());
        assertTrue(new SqliteAgentStore(source).get(run.id()).retained());
        sql("CREATE TRIGGER fail_candidate BEFORE INSERT ON agent_candidate BEGIN SELECT RAISE(ABORT,'injected'); END");
        assertEquals("PERSISTENCE_FAILURE", assertThrows(AgentException.class,
                () -> store.freeze(candidate(run.id()))).code());
        store.advance(run.id(), AgentRunState.FINALIZING, AgentRunState.FAILED, "PERSISTENCE_FAILURE");
        assertTrue(new SqliteAgentStore(source).get(run.id()).retained());
        store.release(run.id(), false);
        assertTrue(new SqliteAgentStore(source).get(run.id()).retained());
    }

    @Test
    void frozenStepBaselineRejectsConcurrentReservationsButNewBaselineIsAllowed() throws Exception {
        var first = run();
        store.reserve(first);
        finalizing(first.id());
        Candidate candidate = candidate(first.id());
        store.freeze(candidate);
        assertFalse(store.get(first.id()).retained());
        store.release(first.id(), false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(() -> reserveResult(), () -> reserveResult()));
            for (var result : results) {
                assertEquals(0, result.get());
            }
        }
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM agent_run")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
        assertEquals(candidate, store.candidate(candidate.id()));
        sql("UPDATE working_copy SET current_revision='" + "c".repeat(40)
                + "',last_verified_revision='" + "c".repeat(40) + "' WHERE id='copy'");
        var next = new AgentProtocol.Run(UUID.randomUUID().toString(), "plan", "step", "copy", "c".repeat(40),
                AgentRunState.CREATED, Instant.now(), null, null, null, false, false, 0, 0, null);
        store.reserve(next);
        assertEquals(AgentRunState.CREATED, store.get(next.id()).state());
    }

    @Test
    void noChangesDiscardsRetentionWithoutBlockingLegitimateRetry() {
        var first = run();
        store.reserve(first);
        finalizing(first.id());
        assertTrue(store.get(first.id()).retained());
        store.advance(first.id(), AgentRunState.FINALIZING, AgentRunState.FAILED, "NO_CHANGES");
        assertFalse(store.get(first.id()).retained());
        store.release(first.id(), false);
        var retry = run();
        store.reserve(retry);
        assertEquals(AgentRunState.CREATED, store.get(retry.id()).state());
    }

    @Test
    void forwardMigrationRestoresV22CrashCutsWithoutRewritingPublishedMigration() throws Exception {
        SQLiteDataSource legacy = new SQLiteDataSource();
        legacy.setUrl("jdbc:sqlite:" + temporary.resolve("legacy-v22.db"));
        Flyway.configure().dataSource(legacy).target("22").load().migrate();
        try (var connection = legacy.getConnection(); var statement = connection.createStatement()) {
            for (String state : List.of("FINALIZING", "FAILED", "INTERRUPTED", "CANCELLED", "CREATED", "SUCCEEDED")) {
                String candidate = state.equals("SUCCEEDED") ? "'candidate'" : "NULL";
                statement.execute("INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,started_at,"
                        + "candidate_id) VALUES('" + state + "','p','s','" + state + "','" + "a".repeat(40)
                        + "','" + state + "','2026-10-22T00:00:00Z'," + candidate + ")");
                if (!state.equals("CREATED")) {
                    statement.execute("INSERT INTO agent_event(run_id,kind,summary,at) VALUES('" + state
                            + "','FINALIZING','historical','2026-10-22T00:00:00Z')");
                }
            }
            statement.execute("INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,started_at,reason) "
                    + "VALUES('empty','p','s','empty','" + "a".repeat(40)
                    + "','FAILED','2026-10-22T00:00:00Z','NO_CHANGES')");
            statement.execute("INSERT INTO agent_event(run_id,kind,summary,at) "
                    + "VALUES('empty','FINALIZING','historical','2026-10-22T00:00:00Z')");
        }
        assertEquals(1, Flyway.configure().dataSource(legacy).target("23").load().migrate().migrationsExecuted);
        assertEquals(0, Flyway.configure().dataSource(legacy).target("23").load().migrate().migrationsExecuted);
        SqliteAgentStore upgraded = new SqliteAgentStore(legacy);
        for (String state : List.of("FINALIZING", "FAILED", "INTERRUPTED", "CANCELLED")) {
            assertTrue(upgraded.get(state).retained(), state);
        }
        for (String state : List.of("CREATED", "SUCCEEDED", "empty")) {
            assertFalse(upgraded.get(state).retained(), state);
        }
    }

    @Test
    void immutableCandidateAndSuccessSurviveReloadWithOrderedEvents() throws Exception {
        var run = run();
        store.reserve(run);
        finalizing(run.id());
        Candidate candidate = candidate(run.id());
        store.freeze(candidate);
        store.release(run.id(), false);
        SqliteAgentStore restarted = new SqliteAgentStore(source);
        assertEquals(candidate, restarted.candidate(candidate.id()));
        assertEquals(AgentRunState.SUCCEEDED, restarted.get(run.id()).state());
        assertTrue(restarted.abandoned().isEmpty());
        var events = restarted.events(run.id(), 0, 100);
        assertEquals(List.of("CREATED", "PREPARING", "RUNNING", "FINALIZING", "SUCCEEDED"),
                events.stream().map(AgentProtocol.Event::kind).toList());
        assertEquals(1, restarted.events(run.id(), events.get(3).sequence(), 100).size());
        assertThrows(SQLException.class, () -> sql("UPDATE agent_candidate SET diff='changed'"));
        assertThrows(SQLException.class, () -> sql("DELETE FROM agent_candidate"));
        var process = new AgentProcess().run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), RestartProbe.class.getName(),
                temporary.resolve("agent.db").toString(), candidate.id(), candidate.sha256()), null, null,
                Duration.ofSeconds(30), 16384, () -> false, Map.of());
        assertEquals(0, process.exitCode(), process.diagnostic());
        assertTrue(process.text().contains("CANDIDATE_RESTART_RELOAD_PASS"));
    }

    @Test
    void failedCandidateInsertRollsBackSuccessAndLeavesNoPartialCandidate() throws Exception {
        var run = run();
        store.reserve(run);
        finalizing(run.id());
        sql("CREATE TRIGGER fail_candidate BEFORE INSERT ON agent_candidate BEGIN SELECT RAISE(ABORT,'injected'); END");
        Candidate candidate = candidate(run.id());
        assertThrows(AgentException.class, () -> store.freeze(candidate));
        assertEquals(AgentRunState.FINALIZING, store.get(run.id()).state());
        assertEquals(null, store.get(run.id()).candidateId());
        assertThrows(AgentException.class, () -> store.candidate(candidate.id()));
        assertEquals(4, store.events(run.id(), 0, 100).size());
    }

    @Test
    void duplicateRequestsAndLegacyWritesCannotRaceReservation() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(() -> reserveResult(), () -> reserveResult()));
            assertEquals(1, results.stream().mapToInt(result -> {
                try {
                    return (Integer) result.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).sum());
        }
        assertThrows(SQLException.class, () -> sql("UPDATE evolution_step SET status='EXECUTING' WHERE id='step'"));
        assertThrows(SQLException.class, () -> sql("UPDATE working_copy SET current_revision='changed' WHERE id='copy'"));
    }

    int reserveResult() {
        try {
            store.reserve(run());
            return 1;
        } catch (AgentException conflict) {
            assertEquals("RUN_CONFLICT", conflict.code());
            return 0;
        }
    }

    @Test
    void cancelledAndInterruptedRunsCanRetryButCannotFreeze() {
        var run = run();
        store.reserve(run);
        finalizing(run.id());
        store.requestCancel(run.id());
        assertThrows(AgentException.class, () -> store.freeze(candidate(run.id())));
        store.advance(run.id(), AgentRunState.FINALIZING, AgentRunState.CANCELLED, "user cancelled");
        store.release(run.id(), false);
        var next = run();
        store.reserve(next);
        assertFalse(store.get(next.id()).state().terminal());
        store.advance(next.id(), AgentRunState.CREATED, AgentRunState.INTERRUPTED, "restart");
        store.release(next.id(), false);
    }

    @Test
    void storedIntegrityMismatchFailsClosed() throws Exception {
        sql("INSERT INTO agent_candidate VALUES('corrupt','unknown','" + "a".repeat(40) + "','" + "b".repeat(40)
                + "',X'64696666','bad','[\"source.java\"]','{}','2026-10-21T00:00:00Z')");
        var failure = assertThrows(AgentException.class, () -> store.candidate("corrupt"));
        assertEquals("CANDIDATE_INTEGRITY", failure.code());
    }

    /** 新 JVM 不共享原测试的连接、对象或 classloader，用于实际进程重启后的读取验证。 */
    public static final class RestartProbe {
        public static void main(String[] arguments) {
            SQLiteDataSource source = new SQLiteDataSource();
            source.setUrl("jdbc:sqlite:" + arguments[0]);
            SqliteAgentStore store = new SqliteAgentStore(source);
            Candidate candidate = store.candidate(arguments[1]);
            if (!candidate.sha256().equals(arguments[2])
                    || store.get(candidate.runId()).state() != AgentRunState.SUCCEEDED) {
                throw new AssertionError("Restart Candidate integrity mismatch");
            }
            System.out.println("CANDIDATE_RESTART_RELOAD_PASS");
        }
    }
}
