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
import java.util.HashSet;
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
                                    : "Official Git/index/content cannot be proven; preserved for inspection");
                }
                if (delivery.state() == Delivery.State.RECOVERY_REQUIRED
                        && "MUTATION_NOT_STARTED".equals(delivery.reason())
                        && position != CandidateDeliveryPort.Position.BASELINE) {
                    // 已记录的外部冲突不能因现场后来碰巧等于 B 而变成一次成功写入。
                    return delivery;
                }
                if (position == CandidateDeliveryPort.Position.BASELINE) {
                    boolean rejectedBeforeMutation = delivery.state() == Delivery.State.RECOVERY_REQUIRED
                            && "MUTATION_NOT_STARTED".equals(delivery.reason());
                    if (delivery.state() != Delivery.State.INTENT && delivery.state() != Delivery.State.DELIVERY_BLOCKED
                            && !rejectedBeforeMutation) {
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

    private static void requireEvidence(Candidate candidate) {
        try {
            JsonNode evidence = new ObjectMapper().readTree(candidate.testEvidence());
            if (evidence == null || !Set.of("TESTED", "NOT_TESTED").contains(evidence.path("status").asText())) {
                throw new AgentException("EVIDENCE_INVALID", "Frozen evidence status cannot be established");
            }
            Set<String> unresolved = new HashSet<>();
            Set<String> failedTests = new HashSet<>();
            for (JsonNode check : evidence.path("checks")) {
                String classification = check.path("classification").asText();
                String directory = check.path("directory").asText(".");
                if (!Set.of("TESTS_PASSED", "BUILD_PASSED_NOT_TESTED").contains(classification)) {
                    unresolved.add(directory);
                    if (Set.of("TESTS_FAILED", "CHECK_RESULT_UNAVAILABLE").contains(classification)) {
                        failedTests.add(directory);
                    }
                } else if (!check.path("stale").asBoolean(true)
                        && Set.of("test", "verify").contains(check.path("goal").asText())
                        && check.path("module").asText("").isBlank()
                        && check.path("selectedTest").asText("").isBlank()
                        && (!failedTests.contains(directory) || classification.equals("TESTS_PASSED"))) {
                    unresolved.remove(directory);
                    failedTests.remove(directory);
                }
            }
            if (!unresolved.isEmpty()) {
                throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Known unresolved checks prohibit Delivery acceptance");
            }
        } catch (IOException failure) {
            throw new AgentException("EVIDENCE_INVALID", "Frozen test evidence is malformed", failure);
        }
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
