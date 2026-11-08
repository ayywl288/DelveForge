package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.quickstart.QuickStart;
import com.ayywl.delveforge.application.quickstart.QuickStartService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 指南是独立 Plan 产物；重试只生成说明，不调用 AgentRun 或重做 Delivery。 */
@RestController
@ConditionalOnProperty(name = "delveforge.agent.enabled", havingValue = "true", matchIfMissing = true)
public class QuickStartController {
    private final QuickStartService service;

    public QuickStartController(QuickStartService service) {
        this.service = service;
    }

    @GetMapping("/api/evolution-plans/{planId}/quick-start")
    public QuickStart get(@PathVariable String planId) {
        return service.get(planId);
    }

    @PostMapping("/api/evolution-plans/{planId}/quick-start")
    public QuickStart generate(@PathVariable String planId) {
        return service.request(planId);
    }
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<java.util.Map<String, String>> error(AgentException failure) {
        return ResponseEntity.status(failure.code().equals("QUICK_START_NOT_READY") ? 409 : 503)
                .body(java.util.Map.of("code", failure.code(), "message", "Quick Start unavailable"));
    }

}
