package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

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
        return eligibility(baseline, official, inspection, original,
                com.ayywl.delveforge.domain.evolution.PreparationProvenance.unchanged(baseline));
    }

    static ExecutionPreparationState eligibility(String baseline, Path official, WorkingCopyInspectionPort inspection,
            Path original, com.ayywl.delveforge.domain.evolution.PreparationProvenance provenance) {
        return eligibility(baseline, official, inspection, original, provenance, false);
    }

    static ExecutionPreparationState eligibility(String baseline, Path official, WorkingCopyInspectionPort inspection,
            Path original, com.ayywl.delveforge.domain.evolution.PreparationProvenance provenance, boolean fullStack) {
        return eligibility(baseline, official, inspection, original, provenance, fullStack, false);
    }

    static ExecutionPreparationState eligibility(String baseline, Path official, WorkingCopyInspectionPort inspection,
            Path original, com.ayywl.delveforge.domain.evolution.PreparationProvenance provenance, boolean fullStack,
            boolean remainingStep) {
        var assetId = new SoftwareAssetId("asset");
        var profileId = new RepositoryProfileId("profile");
        var planId = new EvolutionPlanId("plan");
        var stepId = new EvolutionStepId("step");
        var copyId = new WorkingCopyId("copy");
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY,
                "src/main/java/example/Counter.java", "source", 0.8, true), new RepositoryProfileEvidenceOrigin(profileId));
        var steps = new java.util.ArrayList<>(List.of(EvolutionStep.reconstitute(stepId, planId,
                        "先运行现有测试观察失败，再修复 Counter.next(n) 返回 n+1，新增边界测试并重跑完整测试",
                        fullStack ? "Counter 后端与 Vue 计数交互及相关自动化测试" : "Counter",
                        List.of("发现实际文件并修改", "增加独立的边界测试类", "读取仓库指引并运行本步骤所需的完整测试"),
                        EvolutionStepStatus.READY)));
        if (remainingStep) {
            steps.add(EvolutionStep.reconstitute(new EvolutionStepId("later"), planId,
                    "later", "later", List.of("later"), EvolutionStepStatus.PENDING_CONFIRMATION));
        }
        EvolutionPlan plan = EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), assetId, profileId,
                new CurrentState("current", List.of(), List.of(), List.of()), new TargetState("problem", "target", "difference"),
                List.of(), List.of("修复递增逻辑"), steps, List.of(), List.of(evidence), copyId, EvolutionPlanStatus.ACTIVE);
        WorkingCopy copy = WorkingCopy.reconstitute(copyId, assetId, provenance.originalRevision(), official.toString(), baseline,
                baseline, WorkingCopyStatus.READY, provenance);
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
        when(sandbox.preflight(anyString(), any(), any())).thenReturn(new AgentSandboxPort.Preflight("READY", "fixture ready"));
        when(sandbox.identity(anyString())).thenReturn(new AgentSandboxPort.Identity("container", "image", 21,
                2147483648L, 2000000000L, 256, "none", true));
    }

    @Test
    void failedRunRetainsUnverifiedWorkWithoutCandidateAndReleasesProcesses() throws Exception {
        when(sandbox.retainPartial(anyString())).thenReturn("owned/partial-metadata.json");
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true,\"result\":{\"sourceChanged\":true}}");
        AtomicInteger turn = new AtomicInteger();
        AgentModelPort model = (history, tools) -> turn.getAndIncrement() == 0
                ? new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("edit", "exec_command",
                    "{\"command\":\"sh implement.sh\"}")), 1L)
                : new AgentProtocol.Reply("{\"status\":\"failed\",\"summary\":\"Required service unavailable\"}", List.of(), 1L);
        try (var agent = new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            var run = agent.start("plan", "step", 21);
            var terminal = awaitTerminal(agent, run.id(), 20);
            assertEquals(AgentRunState.FAILED, terminal.state());
            assertNull(terminal.candidateId());
            assertTrue(terminal.retained());
            assertTrue(database.store.events(run.id(), 0, 200).stream()
                    .anyMatch(event -> event.kind().equals("UNVERIFIED_WORK_RETAINED")));
            verify(sandbox).retainPartial(run.id());
            verify(sandbox).cleanup(run.id());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENVIRONMENT_BLOCKED", "SECURITY_BLOCKED", "SELECTION_REQUIRED"})
    void blockedPreflightHasZeroActualRequestsAndTokensAndCleansUp(String outcome) throws Exception {
        when(sandbox.preflight(anyString(), any(), any())).thenReturn(new AgentSandboxPort.Preflight(outcome, "precise fixture diagnosis"));
        AtomicInteger requests = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            requests.incrementAndGet();
            throw new AssertionError("Preflight must stop before model invocation");
        };
        try (var agent = new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            var run = agent.start("plan", "step", 21);
            var terminal = awaitTerminal(agent, run.id(), 20);
            assertEquals(AgentRunState.FAILED, terminal.state());
            assertEquals(outcome, terminal.reason());
            assertEquals(0, requests.get());
            assertEquals(0L, terminal.tokens());
            assertEquals(0, terminal.modelCalls());
            verify(sandbox).cleanup(run.id());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void transfersPersistedTargetStateWithoutLoadingUnrelatedPlanningHistory() throws Exception {
        when(sandbox.checkCapabilities(anyString(), any())).thenReturn(
                "{\"mavenDirectories\":[\".\"],\"frontendProjects\":[],\"frontendProjectCount\":0}");
        var captured = new java.util.concurrent.atomic.AtomicReference<List<AgentProtocol.Message>>();
        AgentModelPort model = (history, tools) -> {
            captured.set(history);
            return new AgentProtocol.Reply("{\"status\":\"failed\",\"summary\":\"fixture stop\"}", List.of(), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            var run = service.start("plan", "step", 21);
            awaitTerminal(service, run.id(), 10);
            var task = org.mockito.ArgumentCaptor.forClass(AgentSandboxPort.Task.class);
            verify(sandbox).prepare(task.capture(), any(), any());
            assertEquals(new AgentSandboxPort.ProductContext("problem", "target", "difference"),
                    task.getValue().productContext());
            String user = captured.get().get(1).text();
            assertTrue(user.contains("\"targetProduct\":\"target\""));
            assertTrue(user.contains("authorizedStep"));
            assertFalse(user.contains("\"acceptanceContext\":"));
            assertFalse(user.contains("userProfile"));
            assertFalse(user.contains("repositoryProfile"));
            assertTrue(captured.get().get(3).text().contains("mavenDirectories"));
            assertTrue(captured.get().get(3).text().contains("不是验证结果或额外授权"));
            verify(sandbox).checkCapabilities(eq(run.id()), any());
        }
    }

    @Test
    void optionalAcceptanceContextIsSanitizedAuditedAndDoesNotReplaceStepAuthorization() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<List<AgentProtocol.Message>>();
        AgentModelPort model = (history, tools) -> {
            captured.set(history);
            return new AgentProtocol.Reply("{\"status\":\"failed\",\"summary\":\"fixture stop\"}", List.of(), 1L);
        };
        String context = "09:00 result must not overwrite 14:00; token=\"private-fixture-value\"";
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            var run = service.start("plan", "step", 21, List.of("."), context);
            awaitTerminal(service, run.id(), 10);
            var task = org.mockito.ArgumentCaptor.forClass(AgentSandboxPort.Task.class);
            verify(sandbox).prepare(task.capture(), any(), any());
            assertEquals("Counter", task.getValue().scope());
            assertTrue(task.getValue().acceptanceContext().contains("09:00"));
            assertFalse(task.getValue().acceptanceContext().contains("private-fixture-value"));
            assertTrue(captured.get().get(1).text().contains("\"acceptanceContext\":"));
            var audit = service.events(run.id(), 0, 100).stream()
                    .filter(event -> event.kind().equals("ACCEPTANCE_CONTEXT")).findFirst().orElseThrow();
            assertEquals(task.getValue().acceptanceContext(), audit.summary());
        }
    }

    @Test
    void oversizedAcceptanceContextIsRejectedBeforeReservingOrPreparingARun() {
        try (var service = service((history, tools) -> {
            throw new AssertionError("No model request expected");
        }, AgentProtocol.Limits.defaults())) {
            assertEquals("INVALID_REQUEST", assertThrows(AgentException.class,
                    () -> service.start("plan", "step", 21, List.of("."), "x".repeat(3001))).code());
            assertTrue(database.store.runs("plan", "step", null, 50).isEmpty());
            verifyNoInteractions(sandbox);
        }
    }

    @Test
    void modelIncompletePersistsBoundedSanitizedExplanationWithoutPromotingItToEvidence() throws Exception {
        String explanation = "后端检查仍受阻。 token=\"private-fixture-value\" " + "待核对".repeat(600);
        String reply = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("status", "failed", "summary", explanation));
        try (var service = service((history, tools) -> new AgentProtocol.Reply(reply, List.of(), 1L),
                AgentProtocol.Limits.defaults())) {
            var run = awaitTerminal(service, service.start("plan", "step", null).id(), 10);
            assertEquals("MODEL_INCOMPLETE", run.reason());
            assertEquals(AgentRunState.FAILED, run.state());
            assertFalse(run.cancelRequested());
            assertEquals(null, run.candidateId());
            var event = service.events(run.id(), 0, 100).stream()
                    .filter(value -> value.kind().equals("MODEL_INCOMPLETE")).findFirst().orElseThrow();
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.summary());
            assertEquals(1, data.path("metadataVersion").asInt());
            assertTrue(data.path("summary").asText().startsWith("后端检查仍受阻。"));
            assertTrue(data.path("summary").asText().length() <= 1200);
            assertFalse(event.summary().contains("private-fixture-value"));
            assertTrue(event.sequence() < service.events(run.id(), 0, 100).stream()
                    .filter(value -> value.kind().equals("FAILED")).findFirst().orElseThrow().sequence());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void invalidVerificationPairReturnsRecoverableFeedbackThenSetupRunsWithoutMetadata() throws Exception {
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true,\"result\":{\"exitCode\":0}}");
        AtomicInteger turn = new AtomicInteger();
        var observed = new java.util.ArrayList<String>();
        AgentModelPort model = (history, tools) -> {
            history.stream().filter(message -> message.role().equals("tool"))
                    .forEach(message -> observed.add(message.text()));
            return switch (turn.getAndIncrement()) {
                case 0 -> new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("invalid", "exec_command",
                        "{\"command\":\"npm ci\",\"verification_scope\":\"frontend\"}")), 1L);
                case 1 -> new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("setup", "exec_command",
                        "{\"command\":\"npm ci\"}")), 1L);
                default -> new AgentProtocol.Reply("{\"status\":\"failed\",\"summary\":\"Checks still required\"}", List.of(), 1L);
            };
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            var run = awaitTerminal(service, service.start("plan", "step", 21).id(), 10);
            assertEquals("MODEL_INCOMPLETE", run.reason());
            assertFalse(run.cancelRequested());
            assertNull(run.candidateId());
            assertTrue(observed.stream().anyMatch(value -> value.contains("VERIFICATION_FIELDS_REQUIRED")));
            verify(sandbox, times(1)).tool(eq(run.id()), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
            var invalid = service.events(run.id(), 0, 100).stream()
                    .filter(event -> event.kind().equals("TOOL_FINISHED") && event.summary().contains("VERIFICATION_FIELDS_REQUIRED"))
                    .findFirst().orElseThrow();
            assertTrue(invalid.summary().contains("npm ci"));
            assertTrue(invalid.summary().contains("mutationNotStarted"));
        }
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
    void policyRejectedToolRecordsTimedCorrelatedUnknownResultAndStillFailsClosed() throws Exception {
        when(sandbox.tool(anyString(), any(), any())).thenThrow(new AgentPolicyRejection(
                AgentPolicyRejection.Rule.SOURCE_CREDENTIALS, AgentPolicyRejection.Stage.PATCH_SOURCE));
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply("", List.of(
                new AgentProtocol.ToolCall("call", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private patch must not leak\"}")), 12L);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals("POLICY_REJECTED", run.reason());
            assertEquals(null, run.candidateId());
            assertEquals(1, run.modelCalls());
            assertEquals(1, run.toolCalls());
            var events = service.events(id, 0, 100);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var start = events.stream().filter(event -> event.kind().equals("TOOL_STARTED")).findFirst().orElseThrow();
            var finish = events.stream().filter(event -> event.kind().equals("TOOL_FINISHED")).findFirst().orElseThrow();
            var result = json.readTree(finish.summary());
            assertEquals(json.readTree(start.summary()).path("toolCallId"), result.path("toolCallId"));
            assertEquals("POLICY_REJECTED", result.path("classification").asText());
            assertEquals("SOURCE_CREDENTIALS", result.path("policyRule").asText());
            assertEquals("PATCH_SOURCE", result.path("policyStage").asText());
            assertEquals(false, result.path("resultRecorded").asBoolean());
            assertTrue(result.path("invocationDurationNs").asLong() > 0);
            assertTrue(finish.sequence() < events.stream().filter(event -> event.kind().equals("FAILED")).findFirst().orElseThrow().sequence());
            assertTrue(events.stream().noneMatch(event -> event.summary().contains("private patch")));
            verify(sandbox, never()).finish(anyString(), any());
            verify(sandbox).cleanup(id);
            assertTrue(database.store.abandoned().isEmpty());
        }
    }

    @Test
    void failedActivityPersistenceCannotReplaceOriginalPolicyRejection() throws Exception {
        var failingStore = spy(database.store);
        doThrow(new AgentException("PERSISTENCE_FAILURE", "private diagnostic"))
                .when(failingStore).event(anyString(), eq("TOOL_FINISHED"), anyString());
        when(sandbox.tool(anyString(), any(), any())).thenThrow(new AgentException("POLICY_REJECTED", "private rejection"));
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply("", List.of(
                new AgentProtocol.ToolCall("call", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private\"}")), 1L);
        try (var service = new CodingAgentService(eligibility, failingStore, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals("POLICY_REJECTED", awaitTerminal(service, id, 10).reason());
            verify(sandbox, never()).finish(anyString(), any());
            verify(sandbox).cleanup(id);
        }
    }

    @Test
    void preMutationSourceRefusalIsCachedAndAllowsOneDifferentSafeOperationWithoutPretendingPatchSucceeded() throws Exception {
        var rejected = new AgentProtocol.ToolCall("protected", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private patch\"}");
        var safeAlternative = new AgentProtocol.ToolCall("other", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"different safe edit\"}");
        when(sandbox.tool(anyString(), eq(rejected), any())).thenThrow(AgentPolicyRejection.sourceEditRejectedBeforeApply());
        when(sandbox.tool(anyString(), eq(safeAlternative), any())).thenReturn("{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\"}}");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AtomicInteger requests = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            if (request > 1) {
                var response = history.stream().filter(message -> message.role().equals("tool")).findFirst().orElseThrow();
                assertEquals("protected", response.callId());
                assertTrue(response.text().contains("\"ok\":false"));
                assertTrue(response.text().contains("SOURCE_CREDENTIALS"));
                assertFalse(response.text().contains("private patch"));
            }
            return request <= 2 ? new AgentProtocol.Reply("", List.of(rejected), 1L)
                    : request == 3 ? new AgentProtocol.Reply("", List.of(safeAlternative), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.SUCCEEDED, run.state());
            assertEquals(2, run.toolCalls());
            verify(sandbox, times(1)).tool(anyString(), eq(rejected), any());
            verify(sandbox, times(1)).tool(anyString(), eq(safeAlternative), any());
            var events = service.events(id, 0, 100);
            var finishes = events.stream().filter(event -> event.kind().equals("TOOL_FINISHED")).toList();
            assertEquals(2, finishes.size());
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var refused = json.readTree(finishes.getFirst().summary());
            assertEquals(false, refused.path("ok").asBoolean());
            assertEquals(true, refused.path("resultRecorded").asBoolean());
            assertEquals(true, refused.path("mutationNotStarted").asBoolean());
            assertEquals(true, refused.path("alternativeAllowed").asBoolean());
            assertEquals("SOURCE_CREDENTIALS", refused.path("policyRule").asText());
            var starts = events.stream().filter(event -> event.kind().equals("TOOL_STARTED")).toList();
            assertEquals(json.readTree(starts.getFirst().summary()).path("toolCallId"), refused.path("toolCallId"));
            assertTrue(events.stream().noneMatch(event -> event.summary().contains("private patch")));
        }
    }

    @Test
    void repeatedUnchangedPreMutationRefusalUsesTheSharedNoProgressRule() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenThrow(AgentPolicyRejection.sourceEditRejectedBeforeApply());
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall(
                "call-" + requests.incrementAndGet(), "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private\"}")), 1L);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals("NO_PROGRESS", run.reason());
            assertEquals(null, run.candidateId());
            assertEquals(3, run.modelCalls());
            assertEquals(3, run.toolCalls());
            verify(sandbox, never()).finish(anyString(), any());
            verify(sandbox).cleanup(id);
            var lastResult = service.events(id, 0, 100).stream().filter(event -> event.kind().equals("TOOL_FINISHED")).toList().getLast();
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastResult.summary());
            assertTrue(data.path("mutationNotStarted").asBoolean());
            assertTrue(data.path("resultRecorded").asBoolean());
            assertTrue(data.path("alternativeAllowed").asBoolean());
            assertTrue(service.events(id, 0, 100).stream().noneMatch(event -> event.kind().startsWith("DELIVERY")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownNoWriteRejectionsCanBeCorrectedWithoutAnIndependentQuota(boolean repeated) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1 || repeated && calls.get() == 2) {
                throw AgentPolicyRejection.patchContentRejectedBeforeApply(
                        new AgentPolicyRejection.RejectedInput("new_text", List.of(2, 4)));
            }
            return "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\"}}";
        });
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AtomicInteger requests = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            if (request == 2) {
                var refusal = history.stream().filter(message -> message.role().equals("tool")).findFirst().orElseThrow();
                assertTrue(refusal.text().contains("PATCH_CREDENTIALS"));
                assertTrue(refusal.text().contains("PATCH_CONTENT"));
                assertTrue(refusal.text().contains("\"mutationNotStarted\":true"));
                assertFalse(refusal.text().contains("private-proposed-body"));
                var location = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> new com.fasterxml.jackson.databind.ObjectMapper().readTree(refusal.text())).path("rejectedInput");
                assertEquals("new_text", location.path("field").asText());
                assertEquals(2, location.path("lines").get(0).asInt());
            }
            String proposed = request == 1 ? "private-proposed-body" : "runtime-generated-value";
            var edit = new AgentProtocol.ToolCall("call-" + request, "edit_file",
                    "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"" + proposed + "\"}");
            return request <= 3 ? new AgentProtocol.Reply("", List.of(edit), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var terminal = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state());
            assertEquals(3, calls.get());
            var finishes = service.events(id, 0, 100).stream().filter(event -> event.kind().equals("TOOL_FINISHED")).toList();
            var recorded = new com.fasterxml.jackson.databind.ObjectMapper().readTree(finishes.getFirst().summary());
            assertTrue(recorded.path("alternativeAllowed").asBoolean());
            assertTrue(recorded.path("mutationNotStarted").asBoolean());
            assertEquals("PATCH_CREDENTIALS", recorded.path("policyRule").asText());
            assertEquals("new_text", recorded.path("rejectedInput").path("field").asText());
            assertTrue(finishes.stream().noneMatch(event -> event.summary().contains("private-proposed-body")));
        }
    }




    @Test
    void postMutationOrUncertainPolicyRejectionNeverAllowsAlternative() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenThrow(new AgentPolicyRejection(
                AgentPolicyRejection.Rule.SOURCE_CAPTURE_LIMIT, AgentPolicyRejection.Stage.SOURCE_CAPTURE));
        AgentModelPort model = (history, tools) -> {
            requests.incrementAndGet();
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("call", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private\"}")), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals("POLICY_REJECTED", awaitTerminal(service, id, 10).reason());
            assertEquals(1, requests.get());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void failureToRecordSafeRefusalPreservesOriginalPolicyAndStopsBeforeAnotherModelRequest() throws Exception {
        var failingStore = spy(database.store);
        doThrow(new AgentException("PERSISTENCE_FAILURE", "private diagnostic"))
                .when(failingStore).event(anyString(), eq("TOOL_FINISHED"), anyString());
        when(sandbox.tool(anyString(), any(), any())).thenThrow(AgentPolicyRejection.sourceEditRejectedBeforeApply());
        try (var service = new CodingAgentService(eligibility, failingStore,
                (history, tools) -> new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("call", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private\"}")), 1L),
                sandbox, new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals("POLICY_REJECTED", run.reason());
            assertEquals(1, run.modelCalls());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void refusalDoesNotCreateAChangeOrPermitCandidateWhenModelClaimsCompletion() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenThrow(AgentPolicyRejection.sourceEditRejectedBeforeApply());
        when(sandbox.finish(anyString(), any())).thenThrow(new AgentException("NO_CHANGES", "No candidate changes"));
        AgentModelPort model = (history, tools) -> requests.incrementAndGet() == 1
                ? new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("call", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private\"}")), 1L)
                : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals("NO_CHANGES", run.reason());
            assertEquals(null, run.candidateId());
            assertTrue(service.events(id, 0, 100).stream().noneMatch(event -> event.kind().equals("SUCCEEDED") || event.kind().startsWith("DELIVERY")));
        }
    }

    @Test
    void changedArgumentsCannotReuseRejectedToolIdentity() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenThrow(AgentPolicyRejection.sourceEditRejectedBeforeApply());
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("same", "edit_file",
                requests.incrementAndGet() == 1 ? "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"first\"}" : "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"changed\"}")), 1L);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals("TOOL_ID_CONFLICT", awaitTerminal(service, id, 10).reason());
            verify(sandbox, times(1)).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void cancellationDuringToolKeepsCancelledRunAndRecordsUncertainCorrelatedResult() throws Exception {
        when(sandbox.tool(anyString(), any(), any())).thenAnswer(invocation -> {
            database.store.requestCancel(invocation.getArgument(0));
            throw new AgentException("CANCELLED", "private cancellation detail");
        });
        try (var service = service((history, tools) -> new AgentProtocol.Reply("", List.of(
                new AgentProtocol.ToolCall("call", "read_file", "{\"path\":\"src/A.java\"}")), 1L), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.CANCELLED, awaitTerminal(service, id, 10).state());
            var finish = service.events(id, 0, 100).stream().filter(event -> event.kind().equals("TOOL_FINISHED")).findFirst().orElseThrow();
            var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(finish.summary());
            assertEquals("CANCELLED", result.path("classification").asText());
            assertEquals(false, result.path("resultRecorded").asBoolean());
            assertTrue(result.path("invocationDurationNs").asLong() > 0);
            assertEquals(1, service.get(id).toolCalls());
            verify(sandbox, never()).finish(anyString(), any());
            verify(sandbox).cleanup(id);
        }
    }

    @Test
    void retiredToolsCannotDispatchToTheSandbox() throws Exception {
        try (var service = service((history, tools) -> new AgentProtocol.Reply("", List.of(
                new AgentProtocol.ToolCall("call", "run_checks", "{}")), 1L), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals("MODEL_PROTOCOL", awaitTerminal(service, id, 10).reason());
            verify(sandbox, never()).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void returnedInvalidArgumentsAreRecoverableButRealInvocationTimingRemainsSeparate() throws Exception {
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":false,\"classification\":\"INVALID_ARGUMENTS\",\"durationMs\":0}");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AtomicInteger requests = new AtomicInteger();
        try (var service = service((history, tools) -> requests.incrementAndGet() == 1
                ? new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("call", "read_file", "{\"path\":\"src/A.java\"}")), 1L)
                : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, id, 10).state());
            var finish = service.events(id, 0, 100).stream().filter(event -> event.kind().equals("TOOL_FINISHED")).findFirst().orElseThrow();
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(finish.summary());
            assertEquals(0, data.path("durationMs").asLong());
            assertTrue(data.path("invocationDurationNs").asLong() > 0);
            assertEquals("INVALID_ARGUMENTS", data.path("classification").asText());
        }
    }

    @Test
    void truncatedResponseCanRegenerateWithoutReplayingCompletedToolsOrPromotingPartialOutput() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn(
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\",\"paths\":[\"src/A.java\"]}}");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            if (request == 1) {
                return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("once", "edit_file",
                        "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"synthetic complete patch\"}")), 10L);
            }
            if (request == 2) {
                throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_TRUNCATED,
                        new AgentModelFailure.Response(true, true, true, AgentModelFailure.FinishReason.LENGTH, 1, 8192, 17L));
            }
            assertTrue(history.stream().anyMatch(message -> message.text().contains("截断")));
            assertTrue(history.stream().anyMatch(message -> message.calls().stream().anyMatch(call -> call.id().equals("once"))));
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 5L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.SUCCEEDED, run.state());
            assertEquals(2, run.modelCalls());
            assertEquals(15L, run.tokens());
            assertEquals(1, run.toolCalls());
            assertEquals(3, requests.get());
            verify(sandbox, times(1)).tool(anyString(), any(), any());
            assertEquals(1, service.events(id, 0, 100).stream().filter(e -> e.kind().equals("MODEL_RESPONSE_RECOVERY")).count());
        }
    }

    @Test
    void repeatedTruncationStopsAfterBoundAndRetainsEverySafeFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            if (request >= 11) {
                throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_TRUNCATED,
                        new AgentModelFailure.Response(true, true, true, AgentModelFailure.FinishReason.LENGTH, 1, 8192, 17L));
            }
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("call-" + request,
                    "read_file", "{\"path\":\"src/Counter.java\",\"start_line\":" + request + "}")), 10L);
        };
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true,\"result\":{\"path\":\"src/Counter.java\",\"content\":\"synthetic\"}}");
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals("MODEL_PROTOCOL", run.reason());
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals(10, run.modelCalls());
            assertEquals(10, run.toolCalls());
            assertEquals(100L, run.tokens());
            var events = new SqliteAgentStore(database.source).events(id, 0, 100);
            assertEquals(13, events.stream().filter(e -> e.kind().equals("MODEL_REQUEST")).count());
            var failures = events.stream().filter(e -> e.kind().equals("MODEL_FAILED")).toList();
            assertEquals(3, failures.size());
            var diagnostic = new com.fasterxml.jackson.databind.ObjectMapper().readTree(failures.getFirst().summary());
            assertEquals(11, diagnostic.path("request").asInt());
            assertEquals("RESPONSE_TRUNCATED", diagnostic.path("reason").asText());
            assertEquals("LENGTH", diagnostic.path("finishReason").asText());
            assertEquals("RESPONSE", diagnostic.path("stage").asText());
            assertEquals(17, diagnostic.path("reportedTokens").asInt());
            assertFalse(diagnostic.path("callReturned").asBoolean());
            assertTrue(failures.getFirst().sequence() < events.stream().filter(e -> e.kind().equals("FAILED")).findFirst().orElseThrow().sequence());
            verify(sandbox, times(10)).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
            assertNoCandidateOrDelivery();
        }
    }

    @Test
    void truncationRecoveryRemainsBoundedWithoutARequestQuota() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            requests.incrementAndGet();
            throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_TRUNCATED,
                    new AgentModelFailure.Response(true, true, true, AgentModelFailure.FinishReason.LENGTH, 1, 8192, 17L));
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals("MODEL_PROTOCOL", awaitTerminal(service, id, 10).reason());
            assertEquals(3, requests.get());
            assertEquals(0, service.get(id).modelCalls());
            verify(sandbox, never()).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void separateResponseCorrectionsDoNotAccumulateAcrossSuccessfulWork(boolean truncated) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn(
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\",\"paths\":[\"Counter.java\"]}}");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        try (var service = service((history, tools) -> {
            int request = requests.incrementAndGet();
            if (request <= 6 && request % 2 == 1) {
                if (truncated) {
                    throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_TRUNCATED,
                            new AgentModelFailure.Response(true, true, true,
                                    AgentModelFailure.FinishReason.LENGTH, 1, 8192, 17L));
                }
                return new AgentProtocol.Reply("continue implementation", List.of(), 1L);
            }
            return request <= 6 ? new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall(
                    "edit-" + request, "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"change " + request + "\"}")), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        }, AgentProtocol.Limits.defaults())) {
            var run = awaitTerminal(service, service.start("plan", "step", null).id(), 10);
            assertEquals(AgentRunState.SUCCEEDED, run.state(), run.reason());
            assertEquals(7, requests.get());
            assertEquals(3, run.toolCalls());
            assertEquals(3, service.events(run.id(), 0, 100).stream().filter(event -> event.kind().equals(
                    truncated ? "MODEL_RESPONSE_RECOVERY" : "COMPLETION_FORMAT_RETRY")).count());
            verify(sandbox, times(3)).tool(anyString(), any(), any());
        }
    }

    @Test
    void transportAndInvalidToolProtocolNeverUseTruncationRecovery() throws Exception {
        for (var reason : List.of(AgentModelFailure.Reason.PROVIDER_CALL_FAILED,
                AgentModelFailure.Reason.TOOL_ARGUMENTS_INVALID)) {
            AtomicInteger requests = new AtomicInteger();
            AgentModelPort model = (history, tools) -> {
                requests.incrementAndGet();
                throw new AgentModelFailure(reason);
            };
            try (var service = service(model, AgentProtocol.Limits.defaults())) {
                String id = service.start("plan", "step", null).id();
                assertEquals(AgentRunState.FAILED, awaitTerminal(service, id, 10).state());
                assertEquals(1, requests.get());
                assertTrue(service.events(id, 0, 100).stream().noneMatch(e -> e.kind().equals("MODEL_RESPONSE_RECOVERY")));
                verify(sandbox, never()).tool(anyString(), any(), any());
                verify(sandbox, never()).finish(anyString(), any());
            }
        }
    }

    @Test
    void malformedCompletionStopsAfterExistingCorrectionBoundWithDistinctDiagnostic() throws Exception {
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply("private-malformed-completion", List.of(), null);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals("MODEL_PROTOCOL", run.reason());
            assertEquals(3, run.modelCalls());
            assertEquals(null, run.tokens());
            var events = service.events(id, 0, 100);
            assertEquals(2, events.stream().filter(e -> e.kind().equals("COMPLETION_FORMAT_RETRY")).count());
            var failed = events.stream().filter(e -> e.kind().equals("MODEL_FAILED")).findFirst().orElseThrow();
            var diagnostic = new com.fasterxml.jackson.databind.ObjectMapper().readTree(failed.summary());
            assertEquals("COMPLETION_FORMAT_INVALID", diagnostic.path("reason").asText());
            assertTrue(diagnostic.path("callReturned").asBoolean());
            assertFalse(events.stream().anyMatch(e -> e.summary().contains("private-malformed")));
            verify(sandbox, never()).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
            assertNoCandidateOrDelivery();
        }
    }

    @Test
    void invalidToolNameProducesDiagnosticAfterReturnedCallButBeforeAnyToolExecutes() throws Exception {
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply("", List.of(
                new AgentProtocol.ToolCall("call", null, "{}")), 5L);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals("MODEL_PROTOCOL", run.reason());
            assertEquals(1, run.modelCalls());
            assertEquals(5L, run.tokens());
            assertEquals(0, run.toolCalls());
            var failed = service.events(id, 0, 100).stream().filter(e -> e.kind().equals("MODEL_FAILED")).findFirst().orElseThrow();
            var diagnostic = new com.fasterxml.jackson.databind.ObjectMapper().readTree(failed.summary());
            assertEquals("TOOL_NAME_INVALID", diagnostic.path("reason").asText());
            assertTrue(diagnostic.path("callReturned").asBoolean());
            verify(sandbox, never()).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
            assertNoCandidateOrDelivery();
        }
    }

    @Test
    void incompatibleSandboxFailsBeforeModelToolsCandidateOrDelivery() throws Exception {
        doThrow(new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE", "Build compatible image"))
                .when(sandbox).prepare(any(), any(), any());
        AgentModelPort model = mock(AgentModelPort.class);
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals("SANDBOX_PROTOCOL_INCOMPATIBLE", run.reason());
            assertEquals(0, run.modelCalls());
            assertEquals(0, run.toolCalls());
            assertEquals(null, run.candidateId());
            assertNoCandidateOrDelivery();
            verifyNoInteractions(model);
            verify(sandbox, never()).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
            assertTrue(service.events(id, 0, 100).stream().noneMatch(event ->
                    List.of("RUNNING", "MODEL_REQUEST", "TOOL_STARTED", "FINALIZING", "SUCCEEDED")
                            .contains(event.kind()) || event.kind().startsWith("DELIVERY")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyConflictAndUncertainApplyNeverBecomeSafeRetryOrCandidate(boolean legacy) throws Exception {
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply("", List.of(
                new AgentProtocol.ToolCall("patch", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"synthetic\"}")), 1L);
        if (legacy) {
            when(sandbox.tool(anyString(), any(), any())).thenReturn(
                    "{\"ok\":true,\"result\":{\"classification\":\"PATCH_CONFLICT\"}}");
        } else {
            when(sandbox.tool(anyString(), any(), any())).thenThrow(
                    new AgentException("PATCH_OUTCOME_UNCERTAIN", "Application result unavailable"));
        }
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals("PATCH_OUTCOME_UNCERTAIN", run.reason());
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals(1, run.modelCalls());
            assertEquals(null, run.candidateId());
            assertNoCandidateOrDelivery();
            verify(sandbox, never()).finish(anyString(), any());
            assertTrue(service.events(id, 0, 100).stream().noneMatch(event ->
                    List.of("MODEL_COMPLETED", "FINALIZING", "SUCCEEDED").contains(event.kind())
                            || event.kind().startsWith("DELIVERY")));
        }
    }

    private void assertNoCandidateOrDelivery() throws Exception {
        try (var connection = database.source.getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("agent_candidate", "candidate_delivery")) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
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
        var call = new AgentProtocol.ToolCall("call-one", "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"a patch\"}");
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cumulativeUsageBeyondFormerLimitDoesNotStopAndUnknownUsageStaysUnknown(boolean unknown) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true,\"durationMs\":12}");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            verify(sandbox, never()).finish(anyString(), any());
            return new AgentProtocol.Reply(request == 3 ? "{\"status\":\"completed\"}" : "",
                    request == 3 ? List.of() : List.of(new AgentProtocol.ToolCall("call-" + request,
                            "read_file", "{\"path\":\"src/Counter.java\"}")), unknown && request == 2 ? null : 150000L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.SUCCEEDED, run.state());
            assertEquals(unknown ? null : 450000L, run.tokens());
            assertEquals(3, run.modelCalls());
            assertEquals(2, run.toolCalls());
            verify(sandbox, times(1)).finish(anyString(), any());
            var events = service.events(id, 0, 100);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var starts = events.stream().filter(event -> event.kind().equals("TOOL_STARTED")).toList();
            var finishes = events.stream().filter(event -> event.kind().equals("TOOL_FINISHED")).toList();
            assertEquals(2, starts.size());
            assertEquals(2, finishes.size());
            for (int i = 0; i < 2; i++) {
                var start = json.readTree(starts.get(i).summary());
                var finish = json.readTree(finishes.get(i).summary());
                assertEquals(start.path("toolCallId"), finish.path("toolCallId"));
                assertEquals("src/Counter.java", finish.path("path").asText());
                assertEquals(1, start.path("metadataVersion").asInt());
                assertTrue(starts.get(i).sequence() < finishes.get(i).sequence());
            }
            assertNotEquals(json.readTree(starts.get(0).summary()).path("toolCallId"),
                    json.readTree(starts.get(1).summary()).path("toolCallId"));
        }
    }

    @Test
    void repeatedUnchangedWorkStopsForNoProgressRatherThanUsage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true}");
        try (var service = service((history, tools) -> new AgentProtocol.Reply("", List.of(
                new AgentProtocol.ToolCall("read-" + requests.incrementAndGet(), "list_files", "{}")), 300000L),
                AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals("NO_PROGRESS", run.reason());
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals(900000L, run.tokens());
            assertEquals(3, run.modelCalls());
            assertEquals(3, run.toolCalls());
            assertEquals(null, run.candidateId());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalReadInputErrorsUseExistingNoProgressAndPreserveResponses(boolean schemaError) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String feedback = com.ayywl.delveforge.application.agent.AgentTools.readError(
                com.ayywl.delveforge.application.agent.AgentTools.ReadError.INVALID_RANGE);
        when(sandbox.tool(anyString(), any(), any())).thenReturn(feedback);
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            for (int prior = 1; prior < request; prior++) {
                String priorId = "invalid-" + prior;
                var responses = history.stream().filter(message -> priorId.equals(message.callId())).toList();
                assertEquals(1, responses.size());
                assertTrue(responses.get(0).text().contains("INVALID_TOOL_ARGUMENT"));
                assertTrue(responses.get(0).text().contains("readNotStarted"));
            }
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("invalid-" + request,
                    "read_file", schemaError ? "{\"path\":\"source.txt\",\"line_count\":\"bad\"}"
                            : "{\"path\":\"source.txt\",\"line_count\":0}")), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals("NO_PROGRESS", run.reason());
            assertEquals(3, run.modelCalls());
            var finishes = service.events(id, 0, 100).stream()
                    .filter(event -> event.kind().equals("TOOL_FINISHED")).toList();
            assertEquals(3, finishes.size());
            assertTrue(finishes.stream().allMatch(event -> event.summary().contains("readNotStarted")));
            verify(sandbox, never()).finish(anyString(), any());
            if (schemaError) {
                verify(sandbox, never()).tool(anyString(), any(), any());
            }
        }
    }

    @Test
    void compressedHistoryRetainsReadErrorCorrectionWithoutUnsafeInput() throws Exception {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(12000, defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(), 10);
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean preserved = new AtomicBoolean();
        when(sandbox.tool(anyString(), any(), any())).thenAnswer(invocation -> {
            AgentProtocol.ToolCall call = invocation.getArgument(1);
            return call.id().equals("call-1")
                    ? com.ayywl.delveforge.application.agent.AgentTools.readError(
                            com.ayywl.delveforge.application.agent.AgentTools.ReadError.RELATIVE_PATH_REQUIRED)
                    : "{\"ok\":true,\"result\":{\"path\":\"source.txt\",\"content\":\"" + "x".repeat(3500) + "\"}}";
        });
        when(sandbox.finish(anyString(), any())).thenThrow(new AgentException("NO_CHANGES", "No changes"));
        AgentModelPort model = (history, tools) -> {
            if (history.stream().anyMatch(message -> message.role().equals("user")
                    && message.text().contains("RELATIVE_PATH_REQUIRED")
                    && message.text().contains("readNotStarted") && message.text().contains("list_files")
                    && !message.text().contains("bad\\\\path"))) {
                preserved.set(true);
            }
            int request = requests.incrementAndGet();
            return request == 6 ? new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L)
                    : new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("call-" + request,
                            "read_file", request == 1 ? "{\"path\":\"bad\\\\path\"}"
                                    : "{\"path\":\"source.txt\",\"start_line\":" + request + "}")), 1L);
        };
        try (var service = service(model, limits)) {
            String id = service.start("plan", "step", null).id();
            assertEquals("NO_CHANGES", awaitTerminal(service, id, 10).reason());
            assertTrue(preserved.get());
        }
    }

    @Test
    void modelWaitRemainsCancellableWithoutAnOverallDeadline() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        try (var service = service((history, tools) -> {
            entered.countDown();
            try {
                new java.util.concurrent.CountDownLatch(1).await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 300000L);
        }, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            service.cancel(id);
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.CANCELLED, run.state());
            assertEquals(null, run.candidateId());
            verify(sandbox, never()).finish(anyString(), any());
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
    void completionObligationsReturnFeedbackBeforeFinalizingAndRepeatedUnchangedCompletionStops() throws Exception {
        when(sandbox.completionFeedback(anyString(), any())).thenReturn(
                "{\"classification\":\"UNRESOLVED_CHECK_FAILURE\",\"reason\":\"Current full check required\"}");
        AtomicInteger calls = new AtomicInteger();
        try (var service = service((history, tools) -> {
            if (calls.incrementAndGet() > 1) {
                assertTrue(history.stream().anyMatch(message -> message.text() != null
                        && message.text().contains("Current full check required")));
            }
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), null);
        }, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var terminal = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, terminal.state());
            assertEquals("NO_PROGRESS", terminal.reason());
            assertEquals(3, calls.get());
            verify(sandbox, never()).finish(anyString(), any());
            assertTrue(service.events(id, 0, 100).stream()
                    .anyMatch(event -> event.kind().equals("COMPLETION_CHECK_REJECTED")));
        }
    }

    @Test
    void correctedCompletionObligationsStillRequireNormalFreeze() throws Exception {
        when(sandbox.completionFeedback(anyString(), any())).thenReturn(
                "{\"classification\":\"UNRESOLVED_CHECK_FAILURE\"}", (String) null);
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        try (var service = service((history, tools) -> new AgentProtocol.Reply(
                "{\"status\":\"completed\"}", List.of(), null), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, id, 10).state());
            verify(sandbox, times(1)).finish(anyString(), any());
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
    void repeatedReadCyclesWithoutNewObservationsNeverFreeze() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true}");
        try (var service = service((history, tools) -> {
            int request = requests.incrementAndGet();
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("read-" + request,
                    "read_file", "{\"path\":\"src/Part" + (request % 2) + ".java\"}")), null);
        }, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var terminal = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, terminal.state());
            assertEquals("NO_PROGRESS", terminal.reason());
            assertEquals(6, terminal.toolCalls());
            assertEquals(null, terminal.candidateId());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void defaultTelemetryContinuesBeyondSixtyModelCallsAndOneHundredSixtyToolCalls() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true}");
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            return request <= 61 ? new AgentProtocol.Reply("", List.of(
                    new AgentProtocol.ToolCall("a-" + request, "read_file", "{\"path\":\"src/a" + request + ".java\"}"),
                    new AgentProtocol.ToolCall("b-" + request, "read_file", "{\"path\":\"src/b" + request + ".java\"}"),
                    new AgentProtocol.ToolCall("c-" + request, "read_file", "{\"path\":\"src/c" + request + ".java\"}")), 10001L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 10001L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 30);
            assertEquals(AgentRunState.SUCCEEDED, run.state());
            assertEquals(62, run.modelCalls());
            assertEquals(183, run.toolCalls());
            assertEquals(620062L, run.tokens());
            verify(sandbox, times(183)).tool(anyString(), any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedConflictCyclesStopWithoutDuplicateFinishEventsOrUnsafeFreeze(boolean reread) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenAnswer(invocation -> {
            AgentProtocol.ToolCall call = invocation.getArgument(1);
            return call.name().equals("read_file") ? "{\"ok\":true,\"result\":{\"path\":\"Counter.java\",\"content\":\"unchanged\"}}"
                    : "{\"ok\":false,\"durationMs\":" + requests.get() + ",\"result\":{\"classification\":\"PATCH_CONFLICT\","
                            + "\"mutationNotStarted\":true,\"conflictReason\":\"CONTEXT_MISMATCH\"}}";
        });
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            var call = reread && request % 2 == 1
                    ? new AgentProtocol.ToolCall("call-" + request, "read_file", "{\"path\":\"Counter.java\"}")
                    : new AgentProtocol.ToolCall("call-" + request, "edit_file", "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"private-same\"}");
            return new AgentProtocol.Reply("", List.of(call), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals("NO_PROGRESS", run.reason());
            assertEquals(reread ? 6 : 3, run.toolCalls());
            verify(sandbox, times(reread ? 6 : 3)).tool(anyString(), any(), any());
            verify(sandbox, never()).finish(anyString(), any());
            assertEquals(null, run.candidateId());
            var finishes = service.events(id, 0, 100).stream().filter(event -> event.kind().equals("TOOL_FINISHED")).toList();
            assertEquals(run.toolCalls(), finishes.size());
            assertTrue(finishes.stream().noneMatch(event -> event.summary().contains("private-")));
            assertTrue(finishes.stream().filter(event -> event.summary().contains("PATCH_CONFLICT"))
                    .allMatch(event -> event.summary().contains("\"mutationNotStarted\":true")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictFeedbackAllowsFurtherCorrectionAfterRereadOrSeveralDistinctAttempts(boolean repeated) throws Exception {
        var actions = new java.util.ArrayList<AgentProtocol.ToolCall>();
        int conflicts = repeated ? 2 : 4;
        for (int index = 0; index < conflicts; index++) {
            String oldText = repeated ? "stale" : "stale-" + index;
            actions.add(new AgentProtocol.ToolCall("edit-" + index, "edit_file",
                    "{\"path\":\"Counter.java\",\"old_text\":\"" + oldText + "\",\"new_text\":\"corrected\"}"));
            actions.add(new AgentProtocol.ToolCall("read-" + index, "read_file", "{\"path\":\"Counter.java\"}"));
        }
        actions.add(new AgentProtocol.ToolCall("corrected", "edit_file",
                "{\"path\":\"Counter.java\",\"old_text\":\"current\",\"new_text\":\"corrected\"}"));
        when(sandbox.tool(anyString(), any(), any())).thenAnswer(invocation -> {
            AgentProtocol.ToolCall call = invocation.getArgument(1);
            if (call.name().equals("read_file")) {
                return "{\"ok\":true,\"result\":{\"path\":\"Counter.java\",\"content\":\"current\"}}";
            }
            return call.id().equals("corrected") ? "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\"}}"
                    : "{\"ok\":false,\"result\":{\"classification\":\"PATCH_CONFLICT\",\"mutationNotStarted\":true}}";
        });
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AtomicInteger requests = new AtomicInteger();
        try (var service = service((history, tools) -> {
            int request = requests.getAndIncrement();
            if (request > 0 && request <= actions.size()) {
                AgentProtocol.ToolCall previous = actions.get(request - 1);
                var response = history.stream().filter(message -> previous.id().equals(message.callId())).toList();
                assertEquals(1, response.size());
                if (previous.id().startsWith("edit-")) {
                    assertTrue(response.getFirst().text().contains("PATCH_CONFLICT"));
                    assertTrue(response.getFirst().text().contains("mutationNotStarted"));
                }
            }
            return request < actions.size() ? new AgentProtocol.Reply("", List.of(actions.get(request)), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        }, AgentProtocol.Limits.defaults())) {
            var run = awaitTerminal(service, service.start("plan", "step", null).id(), 10);
            assertEquals(AgentRunState.SUCCEEDED, run.state(), run.reason());
            assertEquals(actions.size(), run.toolCalls());
            verify(sandbox, times(actions.size())).tool(anyString(), any(), any());
            assertEquals(conflicts, service.events(run.id(), 0, 100).stream()
                    .filter(event -> event.kind().equals("TOOL_FINISHED") && event.summary().contains("PATCH_CONFLICT")).count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"mutationNotStarted\":false}", "{\"mutationNotStarted\":\"true\"}"})
    void conflictWithoutBooleanPreMutationProofStillTerminates(String details) throws Exception {
        String result = details.replace("{", "{\"classification\":\"PATCH_CONFLICT\",");
        result = result.replace(",}", "}");
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":false,\"result\":" + result + "}");
        AtomicInteger requests = new AtomicInteger();
        try (var service = service((history, tools) -> {
            requests.incrementAndGet();
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("edit", "edit_file",
                    "{\"path\":\"Counter.java\",\"old_text\":\"old\",\"new_text\":\"new\"}")), 1L);
        }, AgentProtocol.Limits.defaults())) {
            var run = awaitTerminal(service, service.start("plan", "step", null).id(), 10);
            assertEquals(AgentRunState.FAILED, run.state());
            assertEquals("PATCH_OUTCOME_UNCERTAIN", run.reason());
            assertEquals(1, requests.get());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void conflictDefersRemainingBatchWritesAndReturnsEveryToolIdentityBeforeFreshCorrection() throws Exception {
        var first = new AgentProtocol.ToolCall("conflict", "edit_file",
                "{\"path\":\"Counter.java\",\"old_text\":\"stale\",\"new_text\":\"first\"}");
        var stale = new AgentProtocol.ToolCall("stale-batch-write", "edit_file",
                "{\"path\":\"Counter.java\",\"old_text\":\"first\",\"new_text\":\"second\"}");
        var read = new AgentProtocol.ToolCall("current", "read_file", "{\"path\":\"Counter.java\"}");
        var fresh = new AgentProtocol.ToolCall("fresh", "edit_file",
                "{\"path\":\"Counter.java\",\"old_text\":\"current\",\"new_text\":\"second\"}");
        when(sandbox.tool(anyString(), eq(first), any())).thenReturn("""
                {"ok":false,"result":{"classification":"PATCH_CONFLICT","mutationNotStarted":true}}
                """);
        when(sandbox.tool(anyString(), eq(stale), any())).thenReturn("""
                {"ok":false,"result":{"classification":"PATCH_CONFLICT","mutationNotStarted":true}}
                """);
        when(sandbox.tool(anyString(), eq(read), any())).thenReturn("""
                {"ok":true,"result":{"path":"Counter.java","content":"current"}}
                """);
        when(sandbox.tool(anyString(), eq(fresh), any())).thenReturn("""
                {"ok":true,"result":{"classification":"PATCH_APPLIED"}}
                """);
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AtomicInteger requests = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            int request = requests.incrementAndGet();
            if (request == 1) {
                return new AgentProtocol.Reply("", List.of(first, stale, read), 1L);
            }
            if (request == 2) {
                for (String callId : List.of(first.id(), stale.id(), read.id())) {
                    assertEquals(1, history.stream().filter(message -> callId.equals(message.callId())).count());
                }
                assertTrue(history.stream().anyMatch(message -> stale.id().equals(message.callId())
                        && message.text().contains("PATCH_NOT_EXECUTED")
                        && message.text().contains("mutationNotStarted")));
                return new AgentProtocol.Reply("", List.of(fresh), 1L);
            }
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.SUCCEEDED, run.state());
            assertEquals(3, run.toolCalls());
            verify(sandbox, never()).tool(eq(id), eq(stale), any());
            verify(sandbox).tool(eq(id), eq(fresh), any());
            assertEquals(1, service.events(id, 0, 100).stream()
                    .filter(event -> event.kind().equals("TOOL_NOT_EXECUTED")).count());
        }
    }

    @Test
    void compressedHistoryRetainsConflictCorrectionInstructionAndTarget() throws Exception {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(12000, defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(),
                10);
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean preserved = new AtomicBoolean();
        when(sandbox.tool(anyString(), any(), any())).thenAnswer(invocation -> {
            AgentProtocol.ToolCall call = invocation.getArgument(1);
            return call.name().equals("edit_file")
                    ? "{\"ok\":false,\"result\":{\"classification\":\"PATCH_CONFLICT\",\"mutationNotStarted\":true,\"conflictReason\":\"CONTEXT_MISMATCH\",\"paths\":[\"src/Counter.java\"],\"output\":\"" + "x".repeat(2500) + "\"}}"
                    : "{\"ok\":true,\"result\":{\"path\":\"src/Counter.java\",\"content\":\"" + "x".repeat(2500) + "\"}}";
        });
        when(sandbox.finish(anyString(), any())).thenThrow(new AgentException("NO_CHANGES", "No changes"));
        AgentModelPort model = (history, tools) -> {
            if (history.stream().anyMatch(message -> message.role().equals("user") && message.text().contains("CONTEXT_MISMATCH")
                    && message.text().contains("src/Counter.java") && message.text().contains("重新读取"))) {
                preserved.set(true);
            }
            int request = requests.incrementAndGet();
            return request == 6 ? new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L)
                    : new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("call-" + request,
                    request == 1 ? "edit_file" : "read_file", request == 1 ? "{\"path\":\"src/Example.java\",\"old_text\":\"before\",\"new_text\":\"fixture\"}"
                            : "{\"path\":\"src/Counter.java\",\"start_line\":" + request + "}")), 1L);
        };
        try (var service = service(model, limits)) {
            String id = service.start("plan", "step", null).id();
            assertEquals("NO_CHANGES", awaitTerminal(service, id, 10).reason());
            assertTrue(preserved.get());
        }
    }

    @Test
    void cancellationIsAcknowledgedBeforeAnUninterruptibleModelReturnsAndNeverFreezes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentModelPort model = (history, tools) -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // 夹具模拟不能立即响应中断的外部调用；最终仍须核对取消事实。
                }
            }
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        };
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                var accepted = service.cancel(id);
                assertTrue(accepted.cancelRequested());
                assertFalse(accepted.state().terminal());
                assertTrue(service.cancel(id).cancelRequested());
            } finally {
                release.countDown();
            }
            assertEquals(AgentRunState.CANCELLED, awaitTerminal(service, id, 10).state());
            verify(sandbox, never()).finish(anyString(), any());
            verify(sandbox).cleanup(id);
        }
    }

    @Test
    void safeNumericalUsageIsPersistedWithoutModelContentOrChangingRunTotals() throws Exception {
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AgentModelPort model = (history, tools) -> new AgentProtocol.Reply(
                "{\"status\":\"completed\",\"summary\":\"private model text\"}", List.of(), 30L,
                new AgentProtocol.Usage(20L, 10L, 30L, 5L, null));
        try (var service = service(model, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            var run = awaitTerminal(service, id, 10);
            assertEquals(AgentRunState.SUCCEEDED, run.state());
            assertEquals(30L, run.tokens());
            var event = service.events(id, 0, 100).stream().filter(item -> item.kind().equals("MODEL_USAGE"))
                    .findFirst().orElseThrow();
            var fields = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.summary());
            assertEquals(20L, fields.path("promptTokens").asLong());
            assertEquals(10L, fields.path("completionTokens").asLong());
            assertEquals(5L, fields.path("cacheHitTokens").asLong());
            assertTrue(fields.path("cacheMissTokens").isNull());
            assertFalse(event.summary().contains("private"));
        }
    }

    @Test
    void usageOverflowDoesNotWrapOrFreeze() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        when(sandbox.tool(anyString(), any(), any())).thenReturn("{\"ok\":true}");
        try (var service = service((history, tools) -> new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall(
                "call-" + requests.incrementAndGet(), "list_files", "{}")), requests.get() == 1 ? Long.MAX_VALUE : 1L), AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", null).id();
            assertEquals("USAGE_COUNTER_OVERFLOW", awaitTerminal(service, id, 10).reason());
            assertEquals(Long.MAX_VALUE, service.get(id).tokens());
            verify(sandbox, never()).finish(anyString(), any());
        }
    }

    @Test
    void compressedModelHistoryPreservesActualMavenCheckScope() throws Exception {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(12000, defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(),
                10);
        String result = "{\"ok\":true,\"result\":{\"classification\":\"TESTS_PASSED\",\"goal\":\"test\","
                + "\"directory\":\"nested\",\"module\":\"library\",\"selectedTest\":\"CounterTest\","
                + "\"tests\":1,\"executedTests\":1,\"output\":\"" + "x".repeat(2500) + "\"}}";
        when(sandbox.tool(anyString(), any(), any())).thenAnswer(invocation -> result.replace("\"output\":\"",
                "\"output\":\"" + ((AgentProtocol.ToolCall) invocation.getArgument(1)).id() + ":"));
        when(sandbox.finish(anyString(), any())).thenAnswer(invocation -> database.candidate(invocation.getArgument(0)));
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean scopedSummarySeen = new AtomicBoolean();
        AgentModelPort observing = (history, tools) -> {
            if (history.stream().anyMatch(message -> message.role().equals("user")
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
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("check-" + request, "exec_command",
                    "{\"command\":\"mvn -pl library -Dtest=CounterTest test\",\"directory\":\"nested\",\"verification_scope\":\"CounterTest\",\"check_kind\":\"test\"}")), 1L);
        };
        try (var service = service(observing, limits)) {
            String id = service.start("plan", "step", null).id();
            assertEquals(AgentRunState.SUCCEEDED, awaitTerminal(service, id, 10).state());
            assertTrue(scopedSummarySeen.get(), "Compressed context lost the actual Maven check scope");
            verify(sandbox, times(5)).tool(anyString(), any(), any());
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
    @Test
    void progressPersistsBeforeTheOperationCompletesWithoutAgentUsage() throws Exception {
        when(sandbox.preflight(anyString(), any(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            java.util.function.Consumer<AgentSandboxPort.PreflightProgress> progress = invocation.getArgument(2);
            progress.accept(new AgentSandboxPort.PreflightProgress("MAVEN_CHECK", "STARTED", "backend", "test", "", 0));
            assertTrue(database.store.events(id, 0, 100).stream().anyMatch(event ->
                    event.kind().equals("PREFLIGHT_PROGRESS") && event.summary().contains("MAVEN_CHECK")));
            assertEquals(0, database.store.get(id).toolCalls());
            progress.accept(new AgentSandboxPort.PreflightProgress("MAVEN_CHECK", "COMPLETED", "backend", "test",
                    "DEPENDENCY_MISSING", 23));
            return new AgentSandboxPort.Preflight("ENVIRONMENT_BLOCKED", "{}");
        });
        try (var service = service((history, tools) -> { throw new AssertionError("No model before readiness"); },
                AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", 21).id();
            var terminal = awaitTerminal(service, id, 10);
            assertEquals(0L, terminal.tokens());
            assertEquals(0, terminal.toolCalls());
            assertEquals(4, service.events(id, 0, 100).stream()
                    .filter(event -> event.kind().equals("PREFLIGHT_PROGRESS")).count());
        }
    }

    @Test
    void reconciliationRecordsReleaseOnlyAfterCleanup() {
        var run = database.run();
        database.store.reserve(run);
        database.store.advance(run.id(), AgentRunState.CREATED, AgentRunState.FAILED, "ENVIRONMENT_BLOCKED");
        doThrow(new AgentException("PROCESS_START_FAILED", "sensitive-diagnostic"))
                .doNothing().when(sandbox).cleanup(run.id());
        try (var service = service((history, tools) -> { throw new AssertionError("No model in reconciliation"); },
                AgentProtocol.Limits.defaults())) {
            service.reconcile();
            assertEquals(1, database.store.abandoned().size());
            assertFalse(service.events(run.id(), 0, 100).toString().contains("sensitive-diagnostic"));
            assertTrue(service.events(run.id(), 0, 100).stream().noneMatch(event -> event.kind().equals("RESOURCES_RELEASED")));
            service.reconcile();
            assertTrue(database.store.abandoned().isEmpty());
            assertEquals("RESOURCES_RELEASED", service.events(run.id(), 0, 100).getLast().kind());
        }
    }

}
