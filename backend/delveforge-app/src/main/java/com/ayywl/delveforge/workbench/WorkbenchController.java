package com.ayywl.delveforge.workbench;

import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workbench")
public final class WorkbenchController {
    public record ProjectRequest(String name, String source) {}

    public record Title(String title) {}

    public record Message(String requestId, String text) {}

    private final Workbench store;
    private final ConversationAgent agent;

    public WorkbenchController(Workbench store, ConversationAgent agent) {
        this.store = store;
        this.agent = agent;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "delveforge", "status", "UP");
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() {
        return store.projects();
    }

    @PostMapping("/projects")
    public Map<String, String> project(@RequestBody ProjectRequest input) {
        return Map.of("id", agent.project(input.name(), input.source()));
    }

    @PostMapping("/projects/{id}/open")
    public void open(@PathVariable String id) {
        agent.open(id);
    }

    @GetMapping("/projects/{project}/conversations")
    public List<Map<String, Object>> conversations(@PathVariable String project) {
        return store.conversations(project);
    }

    @PostMapping("/projects/{project}/conversations")
    public Map<String, String> conversation(
            @PathVariable String project, @RequestBody Title title) {
        return Map.of(
                "id", store.conversation(project, ConversationAgent.required(title.title(), 160)));
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> conversation(@PathVariable String id) {
        return Map.of(
                "messages",
                store.timeline(id),
                "runs",
                store.runs(id),
                "activeTools",
                store.activeTools(id));
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Workbench.Ack receive(@PathVariable String id, @RequestBody Message input) {
        return agent.receive(id, input.requestId(), input.text());
    }

    @PostMapping("/conversations/{id}/continue")
    public Map<String, String> resume(@PathVariable String id) {
        return Map.of("id", agent.start(id));
    }

    @PostMapping("/runs/{id}/stop")
    public void stop(@PathVariable String id) {
        agent.stop(id);
    }

    @PostMapping("/runs/{id}/reconcile")
    public void reconcile(@PathVariable String id) {
        agent.recover(id);
    }

    @GetMapping("/conversations/{conversation}/outputs/{id}")
    public Map<String, Object> output(
            @PathVariable String conversation,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "4096") int limit) {
        if (offset < 0 || limit < 1 || limit > 8192) {
            throw new WorkbenchRejection(WorkbenchRejection.Kind.INVALID, "输出分页范围无效");
        }
        return store.output(conversation, id, offset, limit);
    }

    @ExceptionHandler(WorkbenchRejection.class)
    public ResponseEntity<Map<String, String>> rejected(WorkbenchRejection e) {
        HttpStatus status =
                switch (e.kind()) {
                    case INVALID -> HttpStatus.BAD_REQUEST;
                    case BUSY -> HttpStatus.CONFLICT;
                    case MISSING -> HttpStatus.NOT_FOUND;
                    case FORBIDDEN -> HttpStatus.FORBIDDEN;
                };
        return ResponseEntity.status(status).body(Map.of("message", e.getMessage()));
    }
}
