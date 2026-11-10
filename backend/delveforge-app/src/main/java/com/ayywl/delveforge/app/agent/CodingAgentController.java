package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.domain.agent.Candidate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 本机开发调用面；提供受控 Agent、冻结结果与确定性交付入口。 */
@RestController
@ConditionalOnProperty(name = "delveforge.agent.enabled", havingValue = "true", matchIfMissing = true)
public class CodingAgentController {
    private final CodingAgentService service;
    private final AgentStore store;
    private final CandidateDeliveryService delivery;
    private final AgentEventStream streams;

    public CodingAgentController(CodingAgentService service, AgentStore store, CandidateDeliveryService delivery) {
        this.service = service;
        this.store = store;
        this.delivery = delivery;
        this.streams = new AgentEventStream(store);
    }

    @EventListener(ContextClosedEvent.class)
    public void closeStreams() {
        streams.close();
    }

    @GetMapping("/api/evolution-plans/{planId}/steps/{stepId}/agent-runs")
    public List<AgentProtocol.Run> history(@PathVariable String planId, @PathVariable String stepId,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "50") int limit) {
        return store.runs(planId, stepId, before, limit);
    }

    @GetMapping(value = "/api/agent-runs/{id}/stream", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = after;
        if (lastEventId != null) {
            try {
                cursor = Math.max(cursor, Long.parseLong(lastEventId));
            } catch (NumberFormatException failure) {
                throw new AgentException("INVALID_REQUEST", "Invalid event cursor");
            }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .body(streams.open(id, cursor));
    }

    @GetMapping(value = "/api/agent-runs/{id}/logs/{invocationId}", produces = "application/json")
    public String log(@PathVariable String id, @PathVariable String invocationId,
            @RequestParam(defaultValue = "stdout") String stream,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "4000") int limit) {
        return service.readLog(id, invocationId, stream, offset, limit);
    }

    @GetMapping(value = "/api/agent-candidates/{id}/delivery", produces = "application/json")
    public Optional<Delivery> delivery(@PathVariable String id) {
        // 空 Optional 仍经 JSON 转换器写出 null，避免 Spring 把 Java null 作为空响应体。
        return Optional.ofNullable(delivery.get(id));
    }

    @PostMapping("/api/agent-candidates/{id}/delivery")
    public ResponseEntity<Delivery> deliver(@PathVariable String id,
            @RequestBody(required = false) DeliveryRequest request) {
        return ResponseEntity.accepted().body(request != null && request.baselineFailuresAcknowledged()
                ? delivery.acknowledgeBaselineDebtAndSubmit(id, request.expectedDiffSha256(), request.expectedEvidenceSha256())
                : delivery.submit(id));
    }

    public record DeliveryRequest(boolean baselineFailuresAcknowledged, String expectedDiffSha256,
            String expectedEvidenceSha256) {
    }

    @PostMapping("/api/agent-candidates/{id}/complete-plan")
    public Delivery completePlan(@PathVariable String id) {
        return delivery.completePlan(id);
    }

    public record StartRequest(Integer targetJdk, boolean paidUsageAcknowledged, List<String> checkDirectories,
            String acceptanceContext) {
        public StartRequest(Integer targetJdk, boolean paidUsageAcknowledged, List<String> checkDirectories) {
            this(targetJdk, paidUsageAcknowledged, checkDirectories, null);
        }

        public StartRequest(Integer targetJdk, boolean paidUsageAcknowledged) {
            this(targetJdk, paidUsageAcknowledged, List.of());
        }
    }

    @PostMapping("/api/evolution-plans/{planId}/steps/{stepId}/agent-runs")
    public ResponseEntity<AgentProtocol.Run> start(@PathVariable String planId, @PathVariable String stepId,
            @RequestBody StartRequest request) {
        if (!request.paidUsageAcknowledged()) {
            throw new AgentException("PAID_USAGE_ACK_REQUIRED", "请确认本次 API 费用及可用预算");
        }
        return ResponseEntity.accepted().body(service.start(planId, stepId, request.targetJdk(), request.checkDirectories(),
                request.acceptanceContext()));
    }

    @GetMapping("/api/agent-runs/{id}")
    public AgentProtocol.Run get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/api/agent-runs/{id}/events")
    public List<AgentProtocol.Event> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        return service.events(id, after, limit);
    }

    @PostMapping("/api/agent-runs/{id}/cancel")
    public AgentProtocol.Run cancel(@PathVariable String id) {
        return service.cancel(id);
    }

    @GetMapping("/api/agent-candidates/{id}")
    public Map<String, Object> metadata(@PathVariable String id) {
        Candidate candidate = service.candidate(id);
        return Map.of("id", candidate.id(), "runId", candidate.runId(), "baseline", candidate.baseline(),
                "expectedTree", candidate.expectedTree(), "sha256", candidate.sha256(), "changedPaths", candidate.changedPaths(),
                "diffBytes", candidate.diff().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "testEvidence", candidate.testEvidence(), "createdAt", candidate.createdAt(),
                "evidenceSha256", Candidate.digest(candidate.testEvidence()));
    }

    @GetMapping(value = "/api/agent-candidates/{id}/diff", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> diff(@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.candidate(id).diff());
    }

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, String>> error(AgentException failure) {
        int status = switch (failure.code()) {
            case "NOT_FOUND" -> 404;
            case "PAID_USAGE_ACK_REQUIRED" -> 400;
            case "INVALID_REQUEST", "INVALID_ARGUMENTS" -> 400;
            case "RUN_CONFLICT", "INSTANCE_CONFLICT", "DELIVERY_CONFLICT", "BASELINE_MISMATCH",
                    "BASELINE_DEBT_ACK_REQUIRED" -> 409;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("code", failure.code(), "message", "Agent operation could not complete"));
    }
}
