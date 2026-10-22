package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.domain.agent.Candidate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 本机开发调用面；不提供 Candidate Delivery 或任意命令入口。 */
@RestController
@ConditionalOnProperty(name = "delveforge.agent.enabled", havingValue = "true", matchIfMissing = true)
public class CodingAgentController {
    private final CodingAgentService service;

    public CodingAgentController(CodingAgentService service) {
        this.service = service;
    }

    public record StartRequest(Integer targetJdk) {
    }

    @PostMapping("/api/evolution-plans/{planId}/steps/{stepId}/agent-runs")
    public ResponseEntity<AgentProtocol.Run> start(@PathVariable String planId, @PathVariable String stepId,
            @RequestBody(required = false) StartRequest request) {
        return ResponseEntity.accepted().body(service.start(planId, stepId, request == null ? null : request.targetJdk()));
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
                "testEvidence", candidate.testEvidence(), "createdAt", candidate.createdAt());
    }

    @GetMapping(value = "/api/agent-candidates/{id}/diff", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> diff(@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.candidate(id).diff());
    }

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, String>> error(AgentException failure) {
        int status = switch (failure.code()) {
            case "NOT_FOUND" -> 404;
            case "INVALID_REQUEST", "INVALID_ARGUMENTS" -> 400;
            case "RUN_CONFLICT", "INSTANCE_CONFLICT" -> 409;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("code", failure.code(), "message", "Agent operation could not complete"));
    }
}
