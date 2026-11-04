package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.port.workspace.CandidateDeliveryPort;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 确定性交付已冻结结果；模型不参与，异常后的决策只依据实际持久化与 Git 事实。 */
public final class CandidateDeliveryService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(CandidateDeliveryService.class.getName());
    private final AgentStore agents;
    private final DeliveryStore store;
    private final CandidateDeliveryPort workspace;
    private final ExecutionPreparationState eligibility;
    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private final Set<String> discoveryBlocked = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService discovery = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("candidate-delivery-discovery").factory());
    private volatile boolean closed;

    public CandidateDeliveryService(AgentStore agents, DeliveryStore store, CandidateDeliveryPort workspace,
            ExecutionPreparationState eligibility) {
        this.agents = agents;
        this.store = store;
        this.workspace = workspace;
        this.eligibility = eligibility;
    }

    public Delivery get(String candidateId) {
        agents.candidate(candidateId);
        return store.get(candidateId);
    }

    private Delivery reserve(String candidateId) {
        Delivery previous = store.get(candidateId);
        if (previous != null) {
            return previous;
        }
        Candidate candidate = agents.candidate(candidateId);
        AgentProtocol.Run run = agents.get(candidate.runId());
        if (run.state() != AgentRunState.SUCCEEDED || !candidateId.equals(run.candidateId())
                || !candidate.baseline().equals(run.baseline())) {
            throw new AgentException("DELIVERY_CONFLICT", "Candidate is not the frozen successful Run result");
        }
        if (parseEvidence(candidate).path("verificationLevel").asText().equals("BASELINE_DEBT")) {
            requireEvidence(candidate);
        }
        var loaded = eligibility.loadDeliveryMetadata(new EvolutionPlanId(run.planId()), new EvolutionStepId(run.stepId()));
        if (!loaded.copy().id().value().equals(run.workingCopyId())
                || !loaded.copy().lastVerifiedRevision().equals(candidate.baseline())) {
            throw new AgentException("BASELINE_MISMATCH", "Frozen Candidate no longer matches the confirmed Step");
        }
        try {
            return store.reserve(candidate, run, loaded.copy().location(), loaded.asset().location());
        } catch (RuntimeException failure) {
            Delivery actual = store.get(candidateId);
            if (actual != null) {
                return actual;
            }
            throw failure;
        }
    }

    public Delivery submit(String candidateId) {
        if (closed) {
            throw new AgentException("DELIVERY_CONFLICT", "Application is stopping");
        }
        Delivery delivery = reserve(candidateId);
        if (delivery.state() == Delivery.State.COMPLETED) {
            return delivery;
        }
        Thread worker = Thread.ofVirtual().name("candidate-delivery-" + candidateId).unstarted(() -> {
            try {
                deliver(candidateId);
            } finally {
                workers.remove(candidateId);
            }
        });
        if (workers.putIfAbsent(candidateId, worker) == null) {
            worker.start();
        }
        return delivery;
    }

    /** 用户审阅固定 Candidate 的历史失败后显式确认；审计绑定差异和完整证据，不复用 Step 确认。 */
    public Delivery acknowledgeBaselineDebtAndSubmit(String candidateId, String diffSha256, String evidenceSha256) {
        Candidate candidate = agents.candidate(candidateId);
        JsonNode evidence = parseEvidence(candidate);
        AgentCheckEvidence.requireAcceptable(evidence);
        if (!evidence.path("verificationLevel").asText().equals("BASELINE_DEBT")
                || !candidate.sha256().equals(diffSha256)
                || !Candidate.digest(candidate.testEvidence()).equals(evidenceSha256)) {
            throw new AgentException("INVALID_REQUEST", "Acknowledgment must identify the reviewed Candidate and evidence");
        }
        AgentProtocol.Run run = agents.get(candidate.runId());
        if (run.state() != AgentRunState.SUCCEEDED || !candidateId.equals(run.candidateId())) {
            throw new AgentException("DELIVERY_CONFLICT", "Only a frozen successful Run can be reviewed");
        }
        var loaded = eligibility.loadDeliveryMetadata(new EvolutionPlanId(run.planId()), new EvolutionStepId(run.stepId()));
        if (!run.workingCopyId().equals(loaded.copy().id().value())
                || !candidate.baseline().equals(loaded.copy().lastVerifiedRevision())) {
            throw new AgentException("BASELINE_MISMATCH", "Reviewed Candidate no longer matches the confirmed Step");
        }
        agents.event(candidate.runId(), "BASELINE_DEBT_ACKNOWLEDGED", acknowledgment(candidate));
        discoveryBlocked.remove(candidateId);
        return submit(candidateId);
    }

    /** 可同步调用以验证每个持久化截面；跨进程仍由持久化预约和 Workspace 原生锁约束。 */
    public Delivery deliver(String candidateId) {
        Delivery delivery = reserve(candidateId);
        if (delivery.state() == Delivery.State.COMPLETED) {
            return delivery;
        }
        try (var occupation = workspace.occupy(delivery)) {
            delivery = store.get(candidateId);
            try {
                store.requireAuthorized(delivery);
                Candidate candidate = agents.candidate(candidateId);
                AgentProtocol.Run run = agents.get(candidate.runId());
                if (!delivery.runId().equals(candidate.runId()) || !delivery.baseline().equals(candidate.baseline())
                        || !delivery.expectedTree().equals(candidate.expectedTree())
                        || !delivery.planId().equals(run.planId()) || !delivery.stepId().equals(run.stepId())
                        || !delivery.workingCopyId().equals(run.workingCopyId()) || !delivery.baseline().equals(run.baseline())
                        || !candidateId.equals(run.candidateId()) || run.state() != AgentRunState.SUCCEEDED) {
                    throw new AgentException("CANDIDATE_INTEGRITY", "Delivery journal no longer identifies its frozen Run result");
                }
                requireEvidence(candidate);
                CandidateDeliveryPort.Position position = workspace.inspect(delivery);
                if (position == CandidateDeliveryPort.Position.UNKNOWN) {
                    return store.advance(delivery, Delivery.State.RECOVERY_REQUIRED, delivery.revision(),
                            "MUTATION_NOT_STARTED".equals(delivery.reason()) ? delivery.reason()
                                    : Delivery.UNPROVEN_INSPECTION);
                }
                boolean initialInspectionRetry = store.hasOnlyInitialInspectionFailures(delivery);
                if (delivery.state() == Delivery.State.RECOVERY_REQUIRED
                        && ("MUTATION_NOT_STARTED".equals(delivery.reason()) || initialInspectionRetry)
                        && position != CandidateDeliveryPort.Position.BASELINE) {
                    // 已记录的外部冲突不能因现场后来碰巧等于 B 而变成一次成功写入。
                    return delivery;
                }
                if (position == CandidateDeliveryPort.Position.BASELINE) {
                    boolean rejectedBeforeMutation = delivery.state() == Delivery.State.RECOVERY_REQUIRED
                            && "MUTATION_NOT_STARTED".equals(delivery.reason());
                    if (delivery.state() != Delivery.State.INTENT && delivery.state() != Delivery.State.DELIVERY_BLOCKED
                            && !rejectedBeforeMutation && !initialInspectionRetry) {
                        return store.advance(delivery, Delivery.State.RECOVERY_REQUIRED, delivery.revision(),
                                "Official state regressed after a proven mutation");
                    }
                    workspace.replay(delivery, candidate);
                    store.requireAuthorized(delivery);
                    workspace.apply(delivery, candidate);
                    delivery = store.advance(delivery, Delivery.State.APPLIED, null, null);
                    position = CandidateDeliveryPort.Position.APPLIED;
                }
                if (position == CandidateDeliveryPort.Position.APPLIED) {
                    if (delivery.state() == Delivery.State.PUBLISHED) {
                        return store.advance(delivery, Delivery.State.RECOVERY_REQUIRED, delivery.revision(),
                                "Published reference was externally changed");
                    }
                    // 崩溃可能发生在 apply 完成但 APPLIED 尚未提交；重新证明 B，不重放写入。
                    if (delivery.revision() == null) {
                        store.requireAuthorized(delivery);
                        String revision = workspace.createCommit(delivery);
                        delivery = store.advance(delivery, Delivery.State.COMMIT_READY, revision, null);
                    }
                    store.requireAuthorized(delivery);
                    workspace.publish(delivery);
                }
                if (workspace.inspect(delivery) != CandidateDeliveryPort.Position.PUBLISHED) {
                    return store.advance(delivery, Delivery.State.RECOVERY_REQUIRED, delivery.revision(),
                            "Published commit/content identity could not be established");
                }
                if (delivery.state() != Delivery.State.PUBLISHED) {
                    delivery = store.advance(delivery, Delivery.State.PUBLISHED, delivery.revision(), null);
                }
                store.requireAuthorized(delivery);
                store.accept(delivery);
                return store.get(candidateId);
            } catch (RuntimeException failure) {
                // commit 的异常也可能发生在提交之后；先读回，不能把已成功接受的状态降级。
                Delivery actual = store.get(candidateId);
                if (actual.state() == Delivery.State.COMPLETED) {
                    return actual;
                }
                CandidateDeliveryPort.Position position = workspace.inspect(actual);
                String code = failure instanceof AgentException classified ? classified.code() : "DELIVERY_FAILURE";
                if (code.equals("MUTATION_NOT_STARTED")) {
                    return store.advance(actual, position == CandidateDeliveryPort.Position.BASELINE
                            ? Delivery.State.DELIVERY_BLOCKED : Delivery.State.RECOVERY_REQUIRED, actual.revision(), code);
                }
                if (position == CandidateDeliveryPort.Position.APPLIED || position == CandidateDeliveryPort.Position.PUBLISHED) {
                    agents.event(actual.runId(), "DELIVERY_PENDING", code + "; durable checkpoint retained for reconciliation");
                    return actual;
                }
                return store.advance(actual, position == CandidateDeliveryPort.Position.BASELINE
                        ? Delivery.State.DELIVERY_BLOCKED : Delivery.State.RECOVERY_REQUIRED, actual.revision(), code);
            }
        } catch (RuntimeException failure) {
            // 无法获得原生锁时不干预现存写入者；目录/元数据失联则保留预约并明确要求恢复。
            Delivery actual = store.get(candidateId);
            if (actual.state() == Delivery.State.COMPLETED) {
                return actual;
            }
            if (failure instanceof AgentException classified && classified.code().equals("DELIVERY_CONFLICT")) {
                throw failure;
            }
            return store.advance(actual, Delivery.State.RECOVERY_REQUIRED, actual.revision(), "WORKSPACE_UNAVAILABLE");
        }
    }

    /** 显式补齐已接受的最终交付；不调用模型，不重放补丁或创建提交。 */
    public Delivery completePlan(String candidateId) {
        agents.candidate(candidateId);
        Delivery accepted = store.get(candidateId);
        if (accepted == null || accepted.state() != Delivery.State.COMPLETED) {
            throw new AgentException("DELIVERY_CONFLICT", "Plan closure requires a completed Delivery");
        }
        try (var occupation = workspace.occupy(accepted)) {
            if (workspace.inspect(accepted) != CandidateDeliveryPort.Position.PUBLISHED) {
                throw new AgentException("SNAPSHOT_INTEGRITY", "Plan closure requires the unchanged accepted Git state");
            }
            store.completePlan(accepted);
            return store.get(candidateId);
        }
    }

    private static JsonNode parseEvidence(Candidate candidate) {
        try {
            JsonNode evidence = new ObjectMapper().readTree(candidate.testEvidence());
            if (evidence == null) {
                throw new AgentException("EVIDENCE_INVALID", "Frozen test evidence is empty");
            }
            if (evidence.path("evidenceVersion").asInt() == 5) {
                Set<String> paths = new java.util.HashSet<>();
                evidence.path("changedPaths").forEach(path -> paths.add(path.asText()));
                if (paths.size() != evidence.path("changedPaths").size()
                        || !paths.equals(new java.util.HashSet<>(candidate.changedPaths()))) {
                    throw new AgentException("EVIDENCE_INVALID", "Verification paths differ from the immutable Candidate");
                }
            }
            return evidence;
        } catch (IOException failure) {
            throw new AgentException("EVIDENCE_INVALID", "Frozen test evidence is malformed", failure);
        }
    }

    private void requireEvidence(Candidate candidate) {
        JsonNode evidence = parseEvidence(candidate);
        AgentCheckEvidence.requireAcceptable(evidence);
        if (evidence.path("verificationLevel").asText().equals("BASELINE_DEBT")) {
            long after = 0;
            while (true) {
                var events = agents.events(candidate.runId(), after, 100);
                for (var event : events) {
                    if (event.kind().equals("BASELINE_DEBT_ACKNOWLEDGED")
                            && event.summary().equals(acknowledgment(candidate))) {
                        return;
                    }
                    after = event.sequence();
                }
                if (events.size() < 100) {
                    throw new AgentException("BASELINE_DEBT_ACK_REQUIRED", "Review and acknowledge frozen baseline failures before Delivery");
                }
            }
        }
    }

    private static String acknowledgment(Candidate candidate) {
        return candidate.id() + ":" + candidate.sha256() + ":" + Candidate.digest(candidate.testEvidence());
    }

    public void start() {
        for (Delivery delivery : store.unfinished()) {
            if (delivery.state() != Delivery.State.DELIVERY_BLOCKED) {
                submit(delivery.candidateId());
            }
        }
        discovery.scheduleWithFixedDelay(() -> {
            try {
                if (closed) {
                    return;
                }
                for (String candidate : store.pendingCandidates()) {
                    if (discoveryBlocked.contains(candidate)) {
                        continue;
                    }
                    try {
                        submit(candidate);
                    } catch (RuntimeException failure) {
                        discoveryBlocked.add(candidate);
                        String code = failure instanceof AgentException classified ? classified.code() : "DELIVERY_DISCOVERY_FAILED";
                        LOG.log(System.Logger.Level.WARNING, "operation=delivery.discovery candidate={0} code={1}", candidate, code);
                        try {
                            agents.event(agents.candidate(candidate).runId(), "DELIVERY_DISCOVERY_BLOCKED", code);
                        } catch (RuntimeException unavailable) {
                            LOG.log(System.Logger.Level.WARNING, "operation=delivery.discovery.event code=PERSISTENCE_UNAVAILABLE");
                        }
                    }
                }
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "operation=delivery.discovery code=PERSISTENCE_UNAVAILABLE");
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        closed = true;
        discovery.shutdownNow();
        for (Thread worker : workers.values()) {
            try {
                worker.join(15000);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
