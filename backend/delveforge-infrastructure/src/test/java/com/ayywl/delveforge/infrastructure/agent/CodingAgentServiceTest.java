package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyInspectionPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.evolution.WorkingCopyStatus;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingAgentServiceTest {
    @TempDir
    Path temporary;
    SqliteAgentStoreTest database;
    ExecutionPreparationState eligibility;
    AgentSandboxPort sandbox;

    static ExecutionPreparationState eligibility(String baseline, Path official, WorkingCopyInspectionPort inspection) {
        return eligibility(baseline, official, inspection, official.getParent().resolve("original"));
    }

    static ExecutionPreparationState eligibility(String baseline, Path official, WorkingCopyInspectionPort inspection,
            Path original) {
        var assetId = new SoftwareAssetId("asset");
        var profileId = new RepositoryProfileId("profile");
        var planId = new EvolutionPlanId("plan");
        var stepId = new EvolutionStepId("step");
        var copyId = new WorkingCopyId("copy");
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY,
                "src/main/java/example/Counter.java", "source", 0.8, true), new RepositoryProfileEvidenceOrigin(profileId));
        EvolutionPlan plan = EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), assetId, profileId,
                new CurrentState("current", List.of(), List.of(), List.of()), new TargetState("problem", "target", "difference"),
                List.of(), List.of("修复递增逻辑"), List.of(EvolutionStep.reconstitute(stepId, planId,
                        "先运行现有测试观察失败，再修复 Counter.next(n) 返回 n+1，新增边界测试并重跑完整测试", "Counter",
                        List.of("发现实际文件并修改", "增加独立的边界测试类", "运行完整Maven测试"),
                        EvolutionStepStatus.READY)), List.of(), List.of(evidence), copyId, EvolutionPlanStatus.ACTIVE);
        WorkingCopy copy = WorkingCopy.reconstitute(copyId, assetId, baseline, official.toString(), baseline,
                baseline, WorkingCopyStatus.READY);
        SoftwareAsset asset = SoftwareAsset.create(assetId, SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, original.toString(), true,
                "MIT", UsageAuthorization.ALLOWED);
        ProductDirection direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"),
                1, List.of(profileId), "title", "problem", "target", "match", List.of(assetId), "difference", "value", "small",
                List.of(), new DirectionEvidenceSupport(List.of(evidence), List.of(evidence), List.of(evidence)));
        direction.select();
        var plans = mock(EvolutionPlanRepository.class);
        var copies = mock(WorkingCopyRepository.class);
        var assets = mock(SoftwareAssetRepository.class);
        var directions = mock(ProductDirectionRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(copies.findById(copyId)).thenReturn(Optional.of(copy));
        when(assets.findById(assetId)).thenReturn(Optional.of(asset));
        when(directions.findById(direction.id())).thenReturn(Optional.of(direction));
        return new ExecutionPreparationState(plans, copies, assets, directions, inspection, new StepExecutionPolicy());
    }

    @BeforeEach
    void setup() throws Exception {
        database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup();
        eligibility = eligibility("a".repeat(40), temporary.resolve("official"), mock(WorkingCopyInspectionPort.class));
        sandbox = mock(AgentSandboxPort.class);
        when(sandbox.identity(anyString())).thenReturn(new AgentSandboxPort.Identity("container", "image", 21,
                2147483648L, 2000000000L, 256, "none", true));
    }

    CodingAgentService service(AgentModelPort model, AgentProtocol.Limits limits) {
        return new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), limits);
    }

    static AgentProtocol.Run awaitTerminal(CodingAgentService service, String id, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            var run = service.get(id);
            if (run.state().terminal()) {
                // 终态与资源释放不同；测试等待清理阶段也完成。
                Thread.sleep(100);
                return run;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run did not finish: " + service.get(id));
    }

    @Test
    void frozenCandidateRejectsStartBeforeAnyWorkerSandboxOrModelWork() {
        var run = database.run();
        database.store.reserve(run);
        database.finalizing(run.id());
        var candidate = database.candidate(run.id());
        database.store.freeze(candidate);
        database.store.release(run.id(), false);
        AgentModelPort model = mock(AgentModelPort.class);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            assertEquals("RUN_CONFLICT", assertThrows(AgentException.class,
                    () -> service.start("plan", "step", null)).code());
            verifyNoInteractions(model, sandbox);
            assertEquals(candidate, service.candidate(candidate.id()));
            assertTrue(database.store.abandoned().isEmpty());
        }
    }

    @Test
    void repeatedMutatingToolIdentityIsExecutedExactlyOnceBeforeDurableFreeze() throws Exception {
        var call = new AgentProtocol.ToolCall("call-one", "apply_patch", "{\"patch\":\"a patch\"}");
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\"}}");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AgentModelPort model = (history, tools) -> requests.incrementAndGet() <= 2
                ? new AgentProtocol.Reply("", List.of(call), 8L)
                : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 8L);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, id, 10).state());
            verify(sandbox, times(1)).tool(anyString(), any(), any());
            assertEquals(1, service.get(id).toolCalls());
            assertEquals(3, service.get(id).modelCalls());
            assertEquals(24, service.get(id).tokens());
            assertTrue(service.candidate(service.get(id).candidateId()).diff().contains("diff"));
        }
    }

    @Test
    void modelDoneCannotSucceedIfFinalizationFails() throws Exception {
        doThrow(new com.ayywl.delveforge.application.agent.AgentException("REPLAY_MISMATCH", "injected"))
                .when(sandbox).finish(anyString(), any());
        try (var service = service((history, tools) -> new AgentProtocol.Reply("{\"status\":\"completed\"}",
                List.of(), null), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var terminal = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, terminal.state());
            assertEquals("REPLAY_MISMATCH", terminal.reason());
            assertEquals(null, terminal.candidateId());
        }
    }

    @Test
    void failedAttemptCanStartFreshAndFreezeWithoutChangingConfirmedStep() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AgentModelPort model = (history, tools) -> {
            if (requests.incrementAndGet() == 1) {
                throw new AgentException("PROVIDER_UNAVAILABLE", "injected");
            }
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), null);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String failed = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.FAILED, awaitTerminal(service, failed, 10).state());
            String retry = service.start("plan", "step", null).id();
            assertNotEquals(failed, retry);
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, retry, 10).state());
            var loaded = eligibility.loadDeliveryMetadata(new EvolutionPlanId("plan"), new EvolutionStepId("step"));
            assertEquals(EvolutionStepStatus.READY, loaded.plan().step(new EvolutionStepId("step")).status());
            assertEquals("a".repeat(40), loaded.copy().lastVerifiedRevision());
            verify(sandbox, times(2)).prepare(any(), any(), any());
        }
    }

    @Test
    void cancellationStopsProgressAndAllowsFreshRetry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AgentModelPort blocked = (history, tools) -> {
            entered.countDown();
            try {
                Thread.sleep(60000);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), null);
        };
        try (var service = service(blocked, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            service.cancel(id);
            assertEquals(AgentRunState.CANCELLED, awaitTerminal(service, id, 10).state());
            assertTrue(database.store.abandoned().isEmpty());
            String retry = service.start("plan", "step", null).id();
            assertNotEquals(id, retry);
            service.cancel(retry);
            assertEquals(AgentRunState.CANCELLED, awaitTerminal(service, retry, 10).state());
        }
    }

    @Test
    void restartStopsAbandonedRunsButPreservesSuccess() {
        var abandoned = database.run();
        database.store.reserve(abandoned);
        database.store.advance(abandoned.id(), AgentRunState.CREATED, AgentRunState.PREPARING, null);
        try (var service = service((history, tools) -> { throw new AssertionError("Must not resume model"); },
                AgentProtocol.Limits.defaults())) {
            service.reconcile();
            assertEquals(AgentRunState.INTERRUPTED, service.get(abandoned.id()).state());
            verify(sandbox).stop(abandoned.id());
            assertTrue(database.store.abandoned().isEmpty());
        }
    }

    @Test
    void callBudgetExhaustionIsTerminalAndNeverFreezes() throws Exception {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(1, 10, defaults.maxTokens(), defaults.maxContextChars(),
                defaults.maxOutputBytes(), defaults.maxPatchBytes(), defaults.maxFileBytes(), defaults.maxCandidateBytes(),
                defaults.maxFiles(), 30, 10, 0);
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true}");
        try (var service = service((history, tools) -> new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall(
                "call", "list_files", "{}")), null), limits)) {
            String id = service.start("plan", "step", null).id();
            var terminal = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, terminal.state());
            assertEquals("MODEL_CALL_LIMIT", terminal.reason());
        }
    }

    @Test
    void compressedModelHistoryPreservesActualMavenCheckScope() throws Exception {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(defaults.maxModelCalls(), defaults.maxToolCalls(), defaults.maxTokens(),
                12000, defaults.maxOutputBytes(), defaults.maxPatchBytes(), defaults.maxFileBytes(),
                defaults.maxCandidateBytes(), defaults.maxFiles(), 30, 10, 0);
        String result = "{\"ok\":true,\"result\":{\"classification\":\"TESTS_PASSED\",\"goal\":\"test\","
                + "\"directory\":\"nested\",\"module\":\"library\",\"selectedTest\":\"CounterTest\","
                + "\"tests\":1,\"executedTests\":1,\"output\":\"" + "x".repeat(2500) + "\"}}";
        when(sandbox.tool(anyString(), any(), any())).thenReturn(result);
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean scopedSummarySeen = new AtomicBoolean();
        AgentModelPort observing = (history, tools) -> {
            if (history.stream().anyMatch(message -> message.role().equals("system")
                    && message.text().contains("\"directory\":\"nested\"")
                    && message.text().contains("\"module\":\"library\"")
                    && message.text().contains("\"selectedTest\":\"CounterTest\"")
                    && message.text().contains("\"goal\":\"test\""))) {
                scopedSummarySeen.set(true);
            }
            int request = requests.incrementAndGet();
            if (request == 6) {
                return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
            }
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("check-" + request, "run_checks",
                    "{\"goal\":\"test\",\"directory\":\"nested\",\"module\":\"library\",\"test\":\"CounterTest\"}")), 1L);
        };
        try (var service = service(observing, limits)) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, id, 10).state());
            assertTrue(scopedSummarySeen.get(), "Compressed context lost the actual Maven check scope");
            assertTrue(service.events(id, 0, 100).stream().anyMatch(event -> event.kind().equals("TOOL_FINISHED")
                    && event.summary().contains("\"directory\":\"nested\"")));
        }
    }

    @Test
    void markdownCompletionIsParsedButUnstructuredCompletionNeedsBoundedCorrection() throws Exception {
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        var replies = new AtomicInteger();
        try (var service = service((history, tools) -> replies.incrementAndGet() == 1
                ? new AgentProtocol.Reply("工作已完成", List.of(), 2L)
                : new AgentProtocol.Reply("```json\n{\"status\":\"completed\"}\n```", List.of(), 2L),
                AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, id, 10).state());
            assertEquals(2, replies.get());
            assertTrue(service.events(id, 0, 100).stream().anyMatch(event -> event.kind().equals("COMPLETION_FORMAT_RETRY")));
        }
    }

    @Test
    void sqliteFreezeFailureRetainsStoppedCandidateMaterial() throws Exception {
        database.sql("CREATE TRIGGER fail_candidate BEFORE INSERT ON agent_candidate BEGIN SELECT RAISE(ABORT,'injected'); END");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        try (var service = service((history, tools) -> new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), null),
                AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.FAILED, awaitTerminal(service, id, 10).state());
            assertEquals(null, service.get(id).candidateId());
            assertTrue(service.get(id).retained());
            verify(sandbox).stop(id);
        }
    }

    @Test
    void restartCannotReleaseUnconfirmedOwnedProcesses() {
        var run = database.run();
        database.store.reserve(run);
        doThrow(new com.ayywl.delveforge.application.agent.AgentException("DOCKER_UNAVAILABLE", "injected"))
                .when(sandbox).stop(run.id());
        try (var service = service((history, tools) -> { throw new AssertionError("Must not resume"); },
                AgentProtocol.Limits.defaults())) {
            service.reconcile();
            assertEquals(AgentRunState.CREATED, service.get(run.id()).state());
            assertEquals(1, database.store.abandoned().size());
            assertThrows(com.ayywl.delveforge.application.agent.AgentException.class,
                    () -> service.start("plan", "step", null));
        }
    }

    @Test
    void closeDoesNotInterruptAlreadyTerminalCleanup() throws Exception {
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        org.mockito.Mockito.doAnswer(invocation -> {
            Thread.sleep(700);
            return null;
        }).when(sandbox).cleanup(anyString());
        String id;
        try (var service = service((history, tools) -> new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), null),
                AgentProtocol.Limits.defaults())) {
            id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, id, 10).state());
        }
        assertTrue(database.store.abandoned().isEmpty());
        assertTrue(database.store.events(id, 0, 100).stream().noneMatch(event -> event.kind().equals("CLEANUP_BLOCKED")));
    }
}
