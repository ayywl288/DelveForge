package com.ayywl.delveforge.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.workbench.WorkbenchModels;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ProjectFiles.FolderPickerUnavailable;
import com.ayywl.delveforge.application.workbench.ProjectTools;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;

import org.springframework.beans.factory.annotation.Value;
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

    public record Message(String requestId, String text, Integer requestLimit) {}

    public record Continue(Integer requestLimit) {}

    private final Workbench store;
    private final ConversationAgent agent;
    private final ProjectTools tools;

    private final WorkbenchModels model;
    private final AgentContextBudget context;
    private final int commandSeconds;

    public WorkbenchController(Workbench store, ConversationAgent agent, ProjectTools tools, WorkbenchModels model,
            AgentContextBudget context, @Value("${delveforge.workbench.command-seconds:300}") int commandSeconds) {
        this.store = store;
        this.tools = tools;
        this.agent = agent;
        this.model = model;
        this.context = context;
        this.commandSeconds = commandSeconds;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("model", model.describe(model.legacyConfiguration()).model(), "defaultRequestLimit", agent.defaultRequestLimit(),
                "contextWindowTokens", context.contextWindowTokens(), "responseTokens", context.responseTokens(),
                "commandSeconds", commandSeconds, "maxConcurrentRuns", store.capacity());
    }

    @GetMapping("/models")
    public WorkbenchModels.Catalog models() {
        return model.catalog();
    }

    public record ProviderInput(String preset, String workspace, String key) {
        @Override
        public String toString() {
            return "Provider configuration";
        }
    }

    @PostMapping("/providers/{provider}")
    public void saveProvider(@PathVariable String provider, @RequestBody ProviderInput input) {
        model.save(provider, input.preset(), input.workspace(), input.key());
    }

    @PostMapping("/providers/{provider}/delete")
    public void deleteProvider(@PathVariable String provider) {
        model.delete(provider);
    }

    @PostMapping("/models/validate")
    public WorkbenchModels.Validation validate(@RequestBody WorkbenchModels.Choice choice) {
        return model.validate(choice);
    }

    @PostMapping("/conversations/{id}/model")
    public void selectModel(@PathVariable String id, @RequestBody WorkbenchModels.Choice choice) {
        store.selectModel(id, choice);
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

    public record FolderSelection(String path) {}

    @PostMapping("/projects/choose-folder")
    public FolderSelection chooseFolder() {
        return new FolderSelection(agent.chooseSourceFolder().orElse(null));
    }

    @ExceptionHandler(FolderPickerUnavailable.class)
    public ResponseEntity<Map<String, String>> pickerUnavailable(FolderPickerUnavailable failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", failure.getMessage()));
    }

    public record PackageSelection(String name, String plan) {}

    @GetMapping("/projects/{id}/tools")
    public Map<String, Object> tools(@PathVariable String id) {
        return tools.inventory(id);
    }

    @PostMapping("/projects/{id}/tools/preview")
    public Map<String, Object> previewTool(@PathVariable String id, @RequestBody PackageSelection selection) {
        return tools.preview(id, selection.name());
    }

    @PostMapping("/projects/{id}/tools/publish")
    public Map<String, Object> publishTool(@PathVariable String id, @RequestBody PackageSelection selection) {
        return tools.publish(id, selection.name(), selection.plan());
    }

    public record EnvironmentAction(String action) {}

    @PostMapping("/projects/{id}/environment")
    public void maintain(@PathVariable String id, @RequestBody EnvironmentAction input) {
        agent.maintain(id, ConversationAgent.required(input.action(), 20));
    }

    @GetMapping("/projects/{id}/maintenance")
    public Map<String, String> maintenance(@PathVariable String id) {
        return Map.of("operation", store.maintenance(id));
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

    public record Foreground(String projectId, String conversationId, String state) {}

    public record ConversationView(List<Map<String, Object>> messages, List<Map<String, Object>> runs,
            List<Map<String, Object>> activeTools, List<Foreground> foregrounds, WorkbenchModels.Choice model, WorkbenchModels.View modelInfo) {}

    @GetMapping("/conversations/{id}")
    public ConversationView conversation(@PathVariable String id) {
        var foreground = store.owners().stream()
                .map(run -> new Foreground(run.projectId(), run.conversationId(), run.state().name()))
                .toList();
        var frozen = store.activeModel(id);
        var choice = frozen == null ? store.modelChoice(id) : frozen.choice();
        WorkbenchModels.View info = null;
        if (frozen != null) {
            info = model.describe(frozen);
        } else {
            try {
                info = model.preview(choice);
            } catch (WorkbenchRejection unknownModel) {
                // 已退役的选择仍可展示历史；用户可以选择其他可用模型。
            }
        }
        return new ConversationView(store.timeline(id), store.runs(id), store.activeTools(id), foreground, choice, info);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Workbench.Ack receive(@PathVariable String id, @RequestBody Message input) {
        return agent.receive(id, input.requestId(), input.text(), input.requestLimit());
    }

    @PostMapping("/conversations/{id}/continue")
    public Map<String, String> resume(@PathVariable String id, @RequestBody(required = false) Continue options) {
        return Map.of("id", agent.start(id, options == null ? null : options.requestLimit()));
    }

    @PostMapping("/runs/{id}/stop")
    public void stop(@PathVariable String id) {
        agent.stop(id);
    }

    @PostMapping("/runs/{id}/cancel-queue")
    public void cancelQueue(@PathVariable String id) {
        store.cancelQueued(id);
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
