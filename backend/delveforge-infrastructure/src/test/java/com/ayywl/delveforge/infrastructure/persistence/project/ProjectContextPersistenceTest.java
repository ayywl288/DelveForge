package com.ayywl.delveforge.infrastructure.persistence.project;

import com.ayywl.delveforge.application.port.persistence.ProjectContextRepository;
import com.ayywl.delveforge.application.project.ProjectContextConflictException;
import com.ayywl.delveforge.application.project.ProjectFlowContext;
import com.ayywl.delveforge.application.project.ProjectVisibility;
import com.ayywl.delveforge.application.project.ProjectDeletionBlockedException;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = ProjectContextPersistenceTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProjectContextPersistenceTest {
    private static final Path DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "projects.db");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DB::toString);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteProjectContextRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.project")
    static class TestApplication {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired private ProjectContextRepository contexts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProjectContextMapper mapper;

    @BeforeEach
    void resetTestRows() {
        jdbc.update("DELETE FROM project_flow_context");
    }

    private ProjectFlowContext context(String id, String plan, long version, List<String> assets) {
        return new ProjectFlowContext(id, "并发项目", null, assets, List.of(), List.of(), plan,
                Instant.parse("2026-10-11T00:00:00Z"), Instant.parse("2026-10-11T00:01:00Z"), version);
    }

    @Test
    void twoConcurrentCasUpdatesCannotOverwriteOneAnother() throws Exception {
        contexts.create(context("project", null, 0, List.of()), null);
        List<Boolean> results = concurrently(() -> {
            try {
                contexts.update(context("project", null, 1, List.of(UUID.randomUUID().toString())), 0);
                return true;
            } catch (ProjectContextConflictException expected) {
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, contexts.findById("project").orElseThrow().version());
        assertEquals(1, contexts.findById("project").orElseThrow().assetIds().size());
    }

    @Test
    void concurrentAdoptionReturnsOnePersistentProjectIdentity() throws Exception {
        List<String> ids = concurrently(() -> contexts.adoptPlan(
                context(UUID.randomUUID().toString(), "historical-plan", 0, List.of())).id());
        assertEquals(ids.getFirst(), ids.getLast());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM project_flow_context", Integer.class));
        // 使用新 Adapter 读回已提交数据，不依赖服务内缓存。
        assertTrue(contexts.findById(ids.getFirst()).isPresent());
    }

    @Test
    void simultaneousDeletionHasOneWinnerAndIndependentProjectKeepsSharedIdentity() throws Exception {
        contexts.create(context("delete-one", null, 0, List.of("shared-asset")), null);
        contexts.create(context("delete-two", null, 0, List.of("shared-asset")), null);
        List<Boolean> results = concurrently(() -> {
            try {
                contexts.changeVisibility("project:delete-one", 0, true, Instant.now());
                return true;
            } catch (ProjectContextConflictException expected) {
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, contexts.findById("delete-one").orElseThrow().version());
        assertTrue(contexts.findById("delete-one").orElseThrow().deletedAt() != null);
        assertEquals(0, contexts.findById("delete-two").orElseThrow().version());
        assertEquals(List.of("shared-asset"), contexts.findById("delete-two").orElseThrow().assetIds());
    }

    @Test
    void persistedDeletionSurvivesNewAdapterAndConnectionAndBlocksUpdatesUntilExplicitRestore() throws Exception {
        contexts.create(context("durable-deletion", null, 0, List.of()), null);
        ProjectVisibility deleted = contexts.changeVisibility("project:durable-deletion", 0, true, Instant.now());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DB.toAbsolutePath());
                var statement = connection.createStatement();
                var row = statement.executeQuery("SELECT version, deleted_at FROM project_flow_context WHERE id='durable-deletion'")) {
            assertTrue(row.next());
            assertEquals(1, row.getLong(1));
            assertEquals(deleted.deletedAt().toString(), row.getString(2));
        }
        var reopened = new SqliteProjectContextRepository(mapper, new ObjectMapper());
        assertEquals(deleted, reopened.visibility("project:durable-deletion"));
        assertEquals(List.of(), reopened.historyKeys(0, 50));
        assertEquals(List.of("project:durable-deletion"), reopened.historyKeys(0, 50, true));
        assertThrows(ProjectContextConflictException.class,
                () -> contexts.update(context("durable-deletion", null, 2, List.of("new-asset")), 1));
        contexts.changeVisibility("project:durable-deletion", 1, false, Instant.now());
        assertEquals(null, reopened.findById("durable-deletion").orElseThrow().deletedAt());
        assertEquals(2, reopened.findById("durable-deletion").orElseThrow().version());
    }

    @Test
    void failedLegacyDeletionRollsBackMarkerInRealIndependentTransaction() {
        String planId = "unsafe-" + UUID.randomUUID();
        jdbc.update("INSERT INTO evolution_plan(id, product_direction_id, base_asset_id, base_repository_profile_id, "
                + "current_summary, target_problem, target_product, target_differentiation, status, flow_id) "
                + "VALUES(?, 'explicit-direction', 'explicit-asset', 'explicit-repo', 'REST', 'REST', 'REST', 'REST', 'ACTIVE', ?)",
                planId, planId);
        jdbc.update("INSERT INTO evolution_step(id, plan_id, position, goal, scope, status) "
                + "VALUES(?, ?, 0, 'REST', 'src', 'READY')", planId + "-step", planId);
        assertThrows(ProjectDeletionBlockedException.class,
                () -> contexts.changeVisibility("plan:" + planId, 0, true, Instant.now()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM project_legacy_visibility WHERE plan_id=?",
                Integer.class, planId));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM evolution_step WHERE plan_id=?", String.class, planId));
        jdbc.update("DELETE FROM evolution_step WHERE plan_id=?", planId);
        jdbc.update("DELETE FROM evolution_plan WHERE id=?", planId);
    }

    @Test
    void historicalRoundCannotBeDeletedWhileAnotherExplicitRoundInItsFlowIsAuthorized() {
        contexts.create(context("same-flow-root", null, 0, List.of()), null);
        String planId = "pending-" + UUID.randomUUID();
        jdbc.update("INSERT INTO evolution_plan(id, product_direction_id, base_asset_id, base_repository_profile_id, "
                + "current_summary, target_problem, target_product, target_differentiation, status, flow_id) "
                + "VALUES(?, 'explicit-direction', 'explicit-asset', 'explicit-repo', 'REST', 'REST', 'REST', 'REST', 'ACTIVE', 'same-flow-root')",
                planId);
        jdbc.update("INSERT INTO evolution_step(id, plan_id, position, goal, scope, status) "
                + "VALUES(?, ?, 0, 'REST', 'src', 'READY')", planId + "-step", planId);
        assertThrows(ProjectDeletionBlockedException.class,
                () -> contexts.changeVisibility("project:same-flow-root", 0, true, Instant.now()));
        assertEquals(0, contexts.findById("same-flow-root").orElseThrow().version());
        assertEquals(null, contexts.findById("same-flow-root").orElseThrow().deletedAt());
        jdbc.update("DELETE FROM evolution_step WHERE plan_id=?", planId);
        jdbc.update("DELETE FROM evolution_plan WHERE id=?", planId);
    }

    private static <T> List<T> concurrently(Callable<T> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<T> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent test did not start");
                return operation.call();
            };
            var one = pool.submit(task);
            var two = pool.submit(task);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void v15MigrationPreservesExistingIdentityAuthorizationAndRevisionRows() throws Exception {
        Path file = DB.getParent().resolve("upgrade.db");
        Files.createDirectories(file.getParent());
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("14").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO user_profile VALUES('old-user', 'CONFIRMED', 8)");
            statement.executeUpdate("INSERT INTO software_asset(id, type, source, location, read_permission, license_info, "
                    + "usage_authorization, authorization_revision) VALUES('old-asset', 'GIT_REPOSITORY', 'USER_SPECIFIED', "
                    + "'unchanged', 0, '123', 'DENIED', 7)");
            statement.executeUpdate("INSERT INTO working_copy VALUES('old-copy', 'old-asset', 'A', 'never-touch', 'B', 'B', 'EVOLVING')");
        }
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT revision FROM user_profile WHERE id = 'old-user'")) {
                assertTrue(row.next()); assertEquals(8, row.getInt(1));
            }
            try (var row = statement.executeQuery("SELECT license_info, usage_authorization, authorization_revision "
                    + "FROM software_asset WHERE id = 'old-asset'")) {
                assertTrue(row.next()); assertEquals("123", row.getString(1));
                assertEquals("DENIED", row.getString(2)); assertEquals(7, row.getLong(3));
            }
            try (var row = statement.executeQuery("SELECT location, current_revision, last_verified_revision FROM working_copy")) {
                assertTrue(row.next()); assertEquals("never-touch", row.getString(1));
                assertEquals("B", row.getString(2)); assertEquals("B", row.getString(3));
            }
            try (var row = statement.executeQuery("SELECT COUNT(*) FROM project_flow_context")) {
                assertTrue(row.next()); assertEquals(0, row.getInt(1));
            }
        }
    }
}
