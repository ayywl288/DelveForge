package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.port.workspace.CandidateDeliveryPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** 真实 Git 与 SQLite；在持久化边界故障注入后重建服务，绝不使用真实应用数据库。 */
class CandidateDeliveryTest {
    @TempDir
    Path temporary;
    Path original;
    Path copy;
    Path managed;
    String baseline;
    Candidate candidate;
    SQLiteDataSource source;
    SqliteAgentStore agents;
    SqliteDeliveryStore deliveries;
    GitWorkspaceAdapter workspace;
    ExecutionPreparationState eligibility;

    @BeforeEach
    void setup() throws Exception {
        original = Files.createDirectory(temporary.resolve("original"));
        git(original, "init", "-q");
        Files.writeString(original.resolve("Counter.java"), "class Counter { int next(int n) { return n; } }\n");
        git(original, "add", ".");
        git(original, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "baseline");
        baseline = git(original, "rev-parse", "HEAD").strip();
        managed = Files.createDirectory(temporary.resolve("managed"));
        workspace = new GitWorkspaceAdapter(managed);
        copy = Path.of(workspace.provision(new WorkspaceRef(original.toString()), baseline, "copy").workspace().value());
        Path result = temporary.resolve("result");
        git(temporary, "clone", "-q", "--no-hardlinks", original.toString(), result.toString());
        Files.writeString(result.resolve("Counter.java"), "class Counter { int next(int n) { return n + 1; } }\n");
        git(result, "add", ".");
        String diff = git(result, "diff", "--cached", "--full-index", "--no-ext-diff", "--no-renames", baseline);
        String tree = git(result, "write-tree").strip();

        source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temporary.resolve("delivery.db"));
        Flyway.configure().dataSource(source).load().migrate();
        agents = new SqliteAgentStore(source);
        sql("INSERT INTO software_asset VALUES('asset','GIT_REPOSITORY','USER_SPECIFIED','"
                + escape(original.toString()) + "',1,'MIT','ALLOWED',0)");
        sql("INSERT INTO product_direction(id,user_profile_id,user_profile_revision,title,problem,target_product,"
                + "user_fit,differentiation,technical_value,estimated_complexity,status,flow_id) VALUES"
                + "('direction','user',1,'title','problem','target','fit','diff','value','small','SELECTED','standalone')");
        sql("INSERT INTO working_copy(id,source_asset_id,source_revision,location,current_revision,last_verified_revision,status) VALUES('copy','asset','" + baseline + "','" + escape(copy.toString())
                + "','" + baseline + "','" + baseline + "','READY')");
        sql("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,working_copy_id,"
                + "current_summary,target_problem,target_product,target_differentiation,status,flow_id) VALUES"
                + "('plan','direction','asset','profile','copy','current','problem','target','difference','ACTIVE','standalone')");
        sql("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('step','plan',0,'goal','scope','READY')");
        AgentProtocol.Run run = new AgentProtocol.Run(UUID.randomUUID().toString(), "plan", "step", "copy", baseline,
                AgentRunState.CREATED, Instant.now(), null, null, null, false, false, 0, 0, null);
        agents.reserve(run);
        agents.advance(run.id(), AgentRunState.CREATED, AgentRunState.PREPARING, null);
        agents.advance(run.id(), AgentRunState.PREPARING, AgentRunState.RUNNING, null);
        agents.advance(run.id(), AgentRunState.RUNNING, AgentRunState.FINALIZING, null);
        candidate = new Candidate(UUID.randomUUID().toString(), run.id(), baseline, tree, diff, Candidate.digest(diff),
                List.of("Counter.java"), "{\"status\":\"NOT_TESTED\"}", Instant.now());
        agents.freeze(candidate);
        agents.release(run.id(), false);
        eligibility = CodingAgentServiceTest.eligibility(baseline, copy, workspace, original);
        deliveries = new SqliteDeliveryStore(source, eligibility);
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    private void sql(String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private Delivery intent() {
        return deliveries.reserve(candidate, agents.get(candidate.runId()), copy.toString(), original.toString());
    }

    private void withAddedFile(String path) throws Exception {
        Path result = temporary.resolve("result");
        Files.createDirectories(result.resolve(path).getParent());
        Files.writeString(result.resolve(path), "class Added {}\n");
        refreshCandidate();
    }

    private void refreshCandidate() throws Exception {
        Path result = temporary.resolve("result");
        git(result, "add", ".");
        String diff = git(result, "diff", "--cached", "--full-index", "--no-ext-diff", "--no-renames", baseline);
        String paths = new ObjectMapper().writeValueAsString(
                git(result, "diff", "--cached", "--name-only", "--no-renames", baseline).lines().toList());
        String immutableGuard = scalar("SELECT sql FROM sqlite_master WHERE name='agent_candidate_immutable_update'");
        sql("DROP TRIGGER agent_candidate_immutable_update");
        try {
            // 仅构造一致的冻结夹具；正式交付前恢复生产不可变约束。
            sql("UPDATE agent_candidate SET expected_tree='" + git(result, "write-tree").strip()
                    + "',diff=X'" + HexFormat.of().formatHex(diff.getBytes(StandardCharsets.UTF_8)) + "',sha256='"
                    + Candidate.digest(diff) + "',changed_paths='" + escape(paths) + "'");
        } finally {
            sql(immutableGuard);
        }
        candidate = agents.candidate(candidate.id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fileAppearingAtMutationBoundaryIsNeverOverwrittenOrAccepted(boolean ignored) throws Exception {
        withAddedFile("Added.java");
        if (ignored) {
            Files.createDirectories(copy.resolve(".git/info"));
            Files.writeString(copy.resolve(".git/info/exclude"), "Added.java\n");
        }
        Delivery reserved = intent();
        byte[] external = new byte[] {0, 1, 2, '\r', '\n', (byte) 0xff};
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(managed) {
            @Override
            protected void beforeDeliveryMutation(Path target) {
                try {
                    Files.write(target.resolve("Added.java"), external);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        try (var service = new CandidateDeliveryService(agents, deliveries, racing, eligibility)) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertArrayEquals(external, Files.readAllBytes(copy.resolve("Added.java")));
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals(baseline, scalar("SELECT current_revision FROM working_copy"));
        assertEquals(baseline, git(copy, "rev-parse", "HEAD").strip());
        assertEquals(candidate, agents.candidate(candidate.id()));
        assertEquals(reserved.candidateId(), deliveries.get(candidate.id()).candidateId());
        assertEquals("1", scalar("SELECT occupied FROM candidate_delivery"));
        assertEquals("MUTATION_NOT_STARTED", deliveries.get(candidate.id()).reason());
        assertFalse(Files.exists(copy.resolve(".git/delveforge-delivery-incomplete")));
        try (var service = service()) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertEquals("MUTATION_NOT_STARTED", deliveries.get(candidate.id()).reason());
        // 冲突材料由测试用户显式移走；重新证明 A 后交付同一 Candidate，不重新编码。
        Files.move(copy.resolve("Added.java"), temporary.resolve("saved-external"));
        try (var service = service()) {
            assertAccepted(service.deliver(candidate.id()));
        }
        assertArrayEquals(external, Files.readAllBytes(temporary.resolve("saved-external")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedTrackedBytesAtWriteBoundaryArePreservedIncludingDeletion(boolean deletion) throws Exception {
        if (deletion) {
            withAddedFile("Added.java");
            Files.delete(temporary.resolve("result/Counter.java"));
            refreshCandidate();
        }
        byte[] external = "new external tracked draft\r\n".getBytes(StandardCharsets.UTF_8);
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(managed) {
            @Override
            protected void beforeDeliveryMutation(Path target) {
                try {
                    Files.write(target.resolve("Counter.java"), external);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        try (var service = new CandidateDeliveryService(agents, deliveries, racing, eligibility)) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertArrayEquals(external, Files.readAllBytes(copy.resolve("Counter.java")));
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertFalse(Files.exists(copy.resolve("Added.java")));
    }

    @Test
    void parentReplacedWithJunctionOrSymlinkCannotWriteOutsideOfficialCopy() throws Exception {
        withAddedFile("nested/Added.java");
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("Added.java"), "outside bytes\r\n");
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(managed) {
            @Override
            protected void beforeDeliveryMutation(Path target) {
                try {
                    Path link = target.resolve("nested");
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                                .redirectErrorStream(true).start();
                        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        assertEquals(0, process.waitFor(), output);
                    } else {
                        Files.createSymbolicLink(link, outside);
                    }
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        try {
            try (var service = new CandidateDeliveryService(agents, deliveries, racing, eligibility)) {
                assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
            }
            assertEquals("outside bytes\r\n", Files.readString(outside.resolve("Added.java")));
            assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
            assertEquals("READY", scalar("SELECT status FROM evolution_step"));
        } finally {
            Files.deleteIfExists(copy.resolve("nested"));
        }
    }

    @Test
    void changedIndexAtBoundaryIsPreservedWithoutAcceptingCandidate() throws Exception {
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(managed) {
            @Override
            protected void beforeDeliveryMutation(Path target) {
                try {
                    // 模拟不遵守 index.lock 的外部写入者；真实 index 随后必须按字节冲突拒绝。
                    Path foreign = temporary.resolve("foreign-index");
                    Files.write(foreign, new byte[] {1, 2, 3});
                    Files.write(target.resolve(".git/index"), Files.readAllBytes(foreign));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        try (var service = new CandidateDeliveryService(agents, deliveries, racing, eligibility)) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(copy.resolve(".git/index")));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals("class Counter { int next(int n) { return n; } }\n", Files.readString(copy.resolve("Counter.java"))
                .replace("\r\n", "\n"));
    }

    @Test
    void partialCreationRetainsMarkerEvenWhenForeignWorkLaterMakesTreeLookLikeB() throws Exception {
        withAddedFile("Added.java");
        withAddedFile("Second.java");
        byte[] oldIndex = Files.readAllBytes(copy.resolve(".git/index"));
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(managed) {
            @Override
            protected void beforeDeliveryMutation(Path target) {
                try {
                    Files.writeString(target.resolve("Second.java"), "external second file");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        try (var service = new CandidateDeliveryService(agents, deliveries, racing, eligibility)) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertTrue(Files.exists(copy.resolve(".git/delveforge-delivery-incomplete")));
        assertEquals("external second file", Files.readString(copy.resolve("Second.java")));
        assertArrayEquals(oldIndex, Files.readAllBytes(copy.resolve(".git/index")));
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
        Files.writeString(copy.resolve("Added.java"), "class Added {}\n");
        Files.writeString(copy.resolve("Second.java"), "class Added {}\n");
        Files.writeString(copy.resolve("Counter.java"), "class Counter { int next(int n) { return n + 1; } }\n");
        git(copy, "read-tree", candidate.expectedTree());
        try (var service = service()) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals(candidate, agents.candidate(candidate.id()));
    }

    @Test
    void indexFailureAfterSourceMutationKeepsForeignIndexAndIncompleteMarker() throws Exception {
        byte[] externalIndex = new byte[] {1, 2, 3};
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(managed) {
            @Override
            protected void beforeDeliveryIndexPublication(Path target) {
                try {
                    Files.write(target.resolve(".git/index"), externalIndex);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        try (var service = new CandidateDeliveryService(agents, deliveries, racing, eligibility)) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertArrayEquals(externalIndex, Files.readAllBytes(copy.resolve(".git/index")));
        assertTrue(Files.exists(copy.resolve(".git/delveforge-delivery-incomplete")));
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals(candidate, agents.candidate(candidate.id()));
    }

    @Test
    void rejectedForeignTreeEqualToBIsNotAcceptedAsOurCompletedMutation() throws Exception {
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(managed) {
            @Override
            protected void beforeDeliveryMutation(Path target) {
                try {
                    Files.writeString(target.resolve("Counter.java"), "class Counter { int next(int n) { return n + 1; } }\n");
                    try (var indexes = Files.newDirectoryStream(target.resolve(".git"), "delveforge-index-*.delivery")) {
                        Path renderedIndex = indexes.iterator().next();
                        Files.write(target.resolve(".git/index"), Files.readAllBytes(renderedIndex));
                    }
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        try (var service = new CandidateDeliveryService(agents, deliveries, racing, eligibility)) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertEquals("MUTATION_NOT_STARTED", deliveries.get(candidate.id()).reason());
        try (var service = service()) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
    }

    @Test
    void normalAdditionAndDeletionDeliverWithExactTreeAndParent() throws Exception {
        withAddedFile("nested/Added.java");
        Files.delete(temporary.resolve("result/Counter.java"));
        refreshCandidate();
        try (var service = service()) {
            assertAccepted(service.deliver(candidate.id()));
        }
        assertFalse(Files.exists(copy.resolve("Counter.java")));
        assertEquals("class Added {}\n", Files.readString(copy.resolve("nested/Added.java")).replace("\r\n", "\n"));
    }

    @Test
    void additionOnlyCandidateDoesNotRequireAnyChangedBaselineFile() throws Exception {
        Files.writeString(temporary.resolve("result/Counter.java"), Files.readString(original.resolve("Counter.java")));
        withAddedFile("Added.java");
        assertEquals(List.of("Added.java"), candidate.changedPaths());
        try (var service = service()) {
            assertAccepted(service.deliver(candidate.id()));
        }
    }

    @Test
    void renderedCandidatePreservesVersionedCrLfConversion() throws Exception {
        Files.writeString(temporary.resolve("result/.gitattributes"), "*.java text eol=crlf\n");
        refreshCandidate();
        try (var service = service()) {
            assertAccepted(service.deliver(candidate.id()));
        }
        assertTrue(Files.readString(copy.resolve("Counter.java")).endsWith("\r\n"));
    }

    private CandidateDeliveryService service() {
        // 重建所有持久化 Adapter，验证没有进程内缓存参与恢复。
        return new CandidateDeliveryService(new SqliteAgentStore(source), new SqliteDeliveryStore(source, eligibility),
                new GitWorkspaceAdapter(managed), eligibility);
    }

    static String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.toUpperCase().startsWith("GIT_"));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private void assertAccepted(Delivery delivery) throws Exception {
        assertEquals(Delivery.State.COMPLETED, delivery.state(), delivery.reason());
        assertEquals(candidate.expectedTree(), git(copy, "rev-parse", "HEAD^{tree}").strip());
        assertEquals(baseline, git(copy, "rev-parse", "HEAD^").strip());
        assertEquals(delivery.revision(), scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals(delivery.revision(), scalar("SELECT current_revision FROM working_copy"));
        assertEquals("SUCCEEDED", scalar("SELECT status FROM evolution_step"));
        assertEquals("0", scalar("SELECT occupied FROM candidate_delivery"));
        assertEquals(baseline, git(original, "rev-parse", "HEAD").strip());
        assertEquals("", git(original, "status", "--porcelain"));
        workspace.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(original.toString()), delivery.revision());
    }

    @Test
    void frozenResultDeliversAndRepeatedRequestDoesNotCreateAnotherCommit() throws Exception {
        try (var service = service()) {
            Delivery result = service.deliver(candidate.id());
            assertAccepted(result);
            assertEquals(result, service.deliver(candidate.id()));
            assertEquals("2", git(copy, "rev-list", "--count", "HEAD").strip());
            assertEquals("1", scalar("SELECT count(*) FROM agent_run"));
            assertTrue(agents.events(candidate.runId(), 0, 200).stream().anyMatch(e -> e.kind().equals("DELIVERY_COMPLETED")));
        }
    }

    @Test
    void recoversAfterIntentWithoutAnyGitMutation() throws Exception {
        intent();
        try (var service = service()) {
            assertAccepted(service.deliver(candidate.id()));
        }
    }

    @Test
    void recoversAppliedTreeBeforeJournalAdvanceWithoutApplyingAgain() throws Exception {
        Delivery delivery = intent();
        workspace.replay(delivery, candidate);
        workspace.apply(delivery, candidate);
        assertEquals(Delivery.State.INTENT, deliveries.get(candidate.id()).state());
        try (var service = service()) {
            assertAccepted(service.deliver(candidate.id()));
        }
    }

    @Test
    void recoversOrphanCommitWithoutPublishingDuplicateSuccessfulCommits() throws Exception {
        Delivery delivery = intent();
        workspace.replay(delivery, candidate);
        workspace.apply(delivery, candidate);
        String orphan = workspace.createCommit(delivery);
        assertNotNull(orphan);
        try (var service = service()) {
            assertAccepted(service.deliver(candidate.id()));
            assertEquals("2", git(copy, "rev-list", "--count", "HEAD").strip());
        }
    }

    private Delivery beforePublish() {
        Delivery delivery = intent();
        workspace.replay(delivery, candidate);
        workspace.apply(delivery, candidate);
        return deliveries.advance(delivery, Delivery.State.COMMIT_READY, workspace.createCommit(delivery), null);
    }

    @Test
    void recoversAfterGitPublicationBeforeAnySQLitePublicationCheckpoint() throws Exception {
        Delivery delivery = beforePublish();
        workspace.publish(delivery);
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        try (var service = service()) {
            Delivery accepted = service.deliver(candidate.id());
            assertAccepted(accepted);
            assertEquals(delivery.revision(), accepted.revision());
        }
    }

    @Test
    void sqliteFailureAfterGitPublicationKeepsReservationAndCanReopenAndFinish() throws Exception {
        sql("CREATE TRIGGER fail_accept BEFORE UPDATE ON working_copy BEGIN SELECT RAISE(ABORT,'injected'); END");
        Delivery pending;
        try (var service = service()) {
            pending = service.deliver(candidate.id());
        }
        assertEquals(Delivery.State.PUBLISHED, pending.state());
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals("1", scalar("SELECT occupied FROM candidate_delivery"));
        sql("DROP TRIGGER fail_accept");
        try (var service = service()) {
            Delivery result = service.deliver(candidate.id());
            assertAccepted(result);
            assertEquals(pending.revision(), result.revision());
        }
    }

    @Test
    void unavailableOfficialDirectoryRetainsReservationAndReportsRecovery() throws Exception {
        intent();
        Files.move(copy, temporary.resolve("moved-official"));
        try (var service = service()) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertEquals("1", scalar("SELECT occupied FROM candidate_delivery"));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
    }

    @Test
    void changedJournalIdentityCannotSubstituteAnotherTreeDuringRecovery() throws Exception {
        intent();
        sql("UPDATE candidate_delivery SET expected_tree='" + "b".repeat(40) + "'");
        try (var service = service()) {
            assertEquals(Delivery.State.DELIVERY_BLOCKED, service.deliver(candidate.id()).state());
        }
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals("", git(copy, "status", "--porcelain"));
    }

    @Test
    void committedAcceptanceWithLostAcknowledgementIsReadBackAsCompleted() throws Exception {
        SqliteDeliveryStore uncertain = spy(deliveries);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new AgentException("PERSISTENCE_FAILURE", "injected lost acknowledgement after commit");
        }).when(uncertain).accept(any());
        try (var service = new CandidateDeliveryService(agents, uncertain, workspace, eligibility)) {
            assertAccepted(service.deliver(candidate.id()));
        }
        assertEquals("2", git(copy, "rev-list", "--count", "HEAD").strip());
    }

    @Test
    void externalChangeIsPreservedAndTrustCannotAdvance() throws Exception {
        intent();
        Files.writeString(copy.resolve("Counter.java"), "unrelated user work\n");
        try (var service = service()) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertEquals("unrelated user work\n", Files.readString(copy.resolve("Counter.java")));
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
    }

    @Test
    void partialIndexOrUntrackedArtifactsAreNotOverwritten() throws Exception {
        Delivery delivery = intent();
        workspace.replay(delivery, candidate);
        git(copy, "read-tree", candidate.expectedTree());
        Files.writeString(copy.resolve("user.txt"), "preserve me");
        try (var service = service()) {
            assertEquals(Delivery.State.RECOVERY_REQUIRED, service.deliver(candidate.id()).state());
        }
        assertEquals("preserve me", Files.readString(copy.resolve("user.txt")));
        assertEquals(baseline, git(copy, "rev-parse", "HEAD").strip());
    }

    @Test
    void unexpectedTreeBlocksBeforeOfficialSourceMutation() throws Exception {
        sql("DROP TRIGGER agent_candidate_immutable_update");
        sql("UPDATE agent_candidate SET expected_tree='" + "b".repeat(40) + "'");
        try (var service = service()) {
            assertEquals(Delivery.State.DELIVERY_BLOCKED, service.deliver(candidate.id()).state());
        }
        assertEquals(baseline, git(copy, "rev-parse", "HEAD").strip());
        assertEquals("", git(copy, "status", "--porcelain"));
    }

    @Test
    void corruptedDiffIsRejectedWithoutOfficialMutation() throws Exception {
        sql("DROP TRIGGER agent_candidate_immutable_update");
        sql("UPDATE agent_candidate SET diff=X'626164'");
        try (var service = service()) {
            assertEquals("CANDIDATE_INTEGRITY", assertThrows(AgentException.class,
                    () -> service.deliver(candidate.id())).code());
        }
        assertEquals("", git(copy, "status", "--porcelain"));
    }

    @Test
    void publishedReferenceFailureRetainsCommitIdentityAndResumesSafely() throws Exception {
        Delivery delivery = beforePublish();
        Files.writeString(copy.resolve(".git/HEAD.lock"), "external writer");
        assertThrows(RuntimeException.class, () -> workspace.publish(delivery));
        assertEquals(baseline, git(copy, "rev-parse", "HEAD").strip());
        Files.delete(copy.resolve(".git/HEAD.lock"));
        try (var service = service()) {
            Delivery result = service.deliver(candidate.id());
            assertAccepted(result);
            assertEquals(delivery.revision(), result.revision());
        }
    }

    @Test
    void invalidBaselineCannotReserveOrMutateOfficialCopy() throws Exception {
        sql("DROP TRIGGER agent_candidate_immutable_update");
        sql("UPDATE agent_candidate SET baseline='" + "b".repeat(40) + "'");
        try (var service = service()) {
            assertThrows(AgentException.class, () -> service.deliver(candidate.id()));
        }
        assertEquals("0", scalar("SELECT count(*) FROM candidate_delivery"));
        assertEquals(baseline, git(copy, "rev-parse", "HEAD").strip());
    }

    @Test
    void unresolvedTestFailureBlocksDeliveryWithoutChangingTrust() throws Exception {
        sql("DROP TRIGGER agent_candidate_immutable_update");
        sql("UPDATE agent_candidate SET test_evidence='" + escape("{\"status\":\"NOT_TESTED\",\"checks\":["
                + "{\"classification\":\"TESTS_FAILED\",\"directory\":\".\"}]}" ) + "'");
        try (var service = service()) {
            assertEquals(Delivery.State.DELIVERY_BLOCKED, service.deliver(candidate.id()).state());
        }
        assertEquals(baseline, scalar("SELECT last_verified_revision FROM working_copy"));
        assertEquals("READY", scalar("SELECT status FROM evolution_step"));
    }

    @Test
    void durableReservationAndNativeOccupationRejectConflictingOperations() throws Exception {
        Delivery delivery = intent();
        assertThrows(Exception.class, () -> sql("UPDATE working_copy SET location='other'"));
        assertThrows(Exception.class, () -> sql("INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,"
                + "started_at) VALUES('conflicting','plan','step','copy','" + baseline + "','CREATED','2026-10-22T00:00:00Z')"));
        try (var occupation = workspace.occupy(delivery)) {
            assertThrows(AgentException.class, () -> new GitWorkspaceAdapter(managed).occupy(delivery));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(() -> intent(), () -> intent()));
            assertEquals(delivery, results.get(0).get());
            assertEquals(delivery, results.get(1).get());
        }
        assertEquals("1", scalar("SELECT count(*) FROM candidate_delivery"));
        assertFalse(deliveries.pendingCandidates().contains(candidate.id()));
    }
}
