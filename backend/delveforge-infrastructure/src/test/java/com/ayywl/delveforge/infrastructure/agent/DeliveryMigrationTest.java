package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryMigrationTest {
    @TempDir Path temporary;

    @Test
    void v26PreservesOccupiedLegacyCopyAndRejectsProvenanceMutation() throws Exception {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temporary.resolve("v25-occupied.db"));
        Flyway.configure().dataSource(source).target("25").load().migrate();
        String a = "a".repeat(40);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO software_asset VALUES('asset','GIT_REPOSITORY','USER_SPECIFIED','original',1,'MIT','ALLOWED',0)");
            statement.execute("INSERT INTO working_copy VALUES('copy','asset','" + a + "','managed','" + a + "','" + a + "','READY')");
            statement.execute("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,"
                    + "working_copy_id,current_summary,target_problem,target_product,target_differentiation,status) "
                    + "VALUES('plan','direction','asset','profile','copy','current','problem','target','difference','ACTIVE')");
            statement.execute("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('step','plan',0,'goal','scope','READY')");
        }
        new SqliteAgentStore(source).reserve(new AgentProtocol.Run("occupied", "plan", "step", "copy", a,
                AgentRunState.CREATED, Instant.now(), null, null, null, false, false, 0, 0, null));
        assertEquals(3, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT source_revision,current_revision,prepared_revision,preparation_json FROM working_copy")) {
                rows.next();
                assertEquals(a, rows.getString(1));
                assertEquals(a, rows.getString(2));
                org.junit.jupiter.api.Assertions.assertNull(rows.getString(3));
                org.junit.jupiter.api.Assertions.assertNull(rows.getString(4));
                assertEquals(a, com.ayywl.delveforge.infrastructure.persistence.workingcopy.PreparationProvenanceJson
                        .read(a, rows.getString(3), rows.getString(4)).preparedRevision());
            }
            try (var rows = statement.executeQuery("SELECT occupied FROM agent_run WHERE id='occupied'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("UPDATE working_copy SET source_revision='changed' WHERE id='copy'"));
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("INSERT INTO working_copy VALUES('invalid','asset','a','managed2','b','b','READY','b','{}')"));
        }
    }

    @Test
    void v23UpgradePreservesFrozenCandidateAndPlanningAndArchivesLegacyExecutionDeliberately() throws Exception {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temporary.resolve("v23.db"));
        Flyway.configure().dataSource(source).target("23").load().migrate();
        String a = "a".repeat(40);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO software_asset VALUES('asset','GIT_REPOSITORY','USER_SPECIFIED','original',1,'MIT','ALLOWED',0)");
            statement.execute("INSERT INTO working_copy(id,source_asset_id,source_revision,location,current_revision,last_verified_revision,status) VALUES('copy','asset','" + a + "','managed','" + a + "','" + a + "','READY')");
            statement.execute("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,"
                    + "working_copy_id,current_summary,target_problem,target_product,target_differentiation,status) "
                    + "VALUES('plan','direction','asset','profile','copy','current','problem','target','difference','ACTIVE')");
            statement.execute("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('step','plan',0,'goal','scope','READY')");
            statement.execute("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,"
                    + "current_summary,target_problem,target_product,target_differentiation,status) "
                    + "VALUES('old-plan','old-direction','asset','profile','current','problem','target','difference','ACTIVE')");
            statement.execute("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status,baseline_revision,execution_result) "
                    + "VALUES('old-step','old-plan',0,'old goal','scope','EXECUTING','" + a + "','{\"historical\":true}')");
        }
        SqliteAgentStore store = new SqliteAgentStore(source);
        var run = new AgentProtocol.Run("run", "plan", "step", "copy", a, AgentRunState.CREATED,
                Instant.now(), null, null, null, false, false, 0, 0, null);
        store.reserve(run);
        store.advance("run", AgentRunState.CREATED, AgentRunState.PREPARING, null);
        store.advance("run", AgentRunState.PREPARING, AgentRunState.RUNNING, null);
        store.advance("run", AgentRunState.RUNNING, AgentRunState.FINALIZING, null);
        Candidate frozen = new Candidate("candidate", "run", a, "b".repeat(40), "diff\n", Candidate.digest("diff\n"),
                List.of("Counter.java"), "{\"status\":\"NOT_TESTED\"}", Instant.now());
        store.freeze(frozen);
        store.release("run", false);
        assertEquals(5, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
        assertEquals(0, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
        assertEquals(frozen, new SqliteAgentStore(source).candidate("candidate"));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT status FROM evolution_plan WHERE id='plan'")) {
                rows.next(); assertEquals("ACTIVE", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT status FROM evolution_step WHERE id='step'")) {
                rows.next(); assertEquals("READY", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT status,execution_result FROM legacy_evolution_execution WHERE id='old-step'")) {
                rows.next(); assertEquals("EXECUTING", rows.getString(1)); assertEquals("{\"historical\":true}", rows.getString(2));
            }
            try (var rows = statement.executeQuery("SELECT p.status,s.status FROM evolution_plan p JOIN evolution_step s "
                    + "ON s.plan_id=p.id WHERE s.id='old-step'")) {
                rows.next(); assertEquals("SUPERSEDED", rows.getString(1)); assertEquals("CANCELLED", rows.getString(2));
            }
        }
    }
}
