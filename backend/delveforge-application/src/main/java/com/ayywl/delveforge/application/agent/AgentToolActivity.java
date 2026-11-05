package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 持久化工具活动的展示白名单；不保存参数正文，也不改变工具执行与模型历史。 */
public final class AgentToolActivity {
    private static final Set<String> PROTECTED = Set.of(".git", ".hg", ".svn", ".ssh", ".aws", ".gnupg",
            ".m2", ".npmrc", ".netrc", ".pypirc");
    private final ObjectMapper json = new ObjectMapper();
    private final RepositorySecretPolicy secrets;
    private final AgentProtocol.Limits limits;

    public AgentToolActivity(RepositorySecretPolicy secrets, AgentProtocol.Limits limits) {
        this.secrets = secrets;
        this.limits = limits;
    }

    public String started(AgentProtocol.ToolCall call, String identity) {
        return encode(base(call, identity));
    }

    public String finished(AgentProtocol.ToolCall call, String identity, String result) {
        return finished(call, identity, result, null);
    }

    public String finished(AgentProtocol.ToolCall call, String identity, String result, Long invocationDurationNs) {
        Map<String, Object> summary = base(call, identity);
        timing(summary, invocationDurationNs);
        try {
            JsonNode response = json.readTree(result);
            if (response == null || !response.isObject()) {
                throw new AgentException("TOOL_PROTOCOL", "Tool result must be structured JSON");
            }
            JsonNode details = response.path("result");
            if (response.path("ok").isBoolean()) {
                summary.put("ok", response.path("ok").asBoolean());
            }
            String classification = response.path("classification").asText(details.path("classification").asText(""));
            if (classification.matches("[A-Z][A-Z0-9_]{0,63}") && unchanged(classification)) {
                summary.put("classification", classification);
            }
            if (AgentTools.mutation(call.name()) && classification.equals("POLICY_REJECTED")
                    && ((response.path("policyRule").asText().equals("SOURCE_CREDENTIALS")
                            && response.path("policyStage").asText().equals("PATCH_SOURCE"))
                        || (response.path("policyRule").asText().equals("PATCH_CREDENTIALS")
                            && response.path("policyStage").asText().equals("PATCH_CONTENT")))
                    && response.path("mutationNotStarted").asBoolean() && response.path("alternativeAllowed").asBoolean()) {
                summary.put("policyRule", response.path("policyRule").asText());
                summary.put("policyStage", response.path("policyStage").asText());
                summary.put("mutationNotStarted", true);
                summary.put("alternativeAllowed", true);
                summary.put("resultRecorded", true);
                proposalLocation(summary, response.path("rejectedInput"));
            }
            count(summary, response, "durationMs");
            if (AgentTools.readOnly(call.name()) && classification.equals("INVALID_TOOL_ARGUMENT")
                    && details.path("readNotStarted").asBoolean() && details.path("mutationNotStarted").asBoolean()) {
                for (AgentTools.ReadError reason : AgentTools.ReadError.values()) {
                    if (reason.name().equals(details.path("reason").asText())) {
                        summary.put("reason", reason.name());
                        summary.put("readNotStarted", true);
                        summary.put("mutationNotStarted", true);
                        summary.put("resultRecorded", true);
                    }
                }
            }
            if (AgentTools.mutation(call.name()) && classification.equals("PATCH_CONFLICT")
                    && details.path("mutationNotStarted").asBoolean()) {
                summary.put("mutationNotStarted", true);
                String diagnostic = details.path("patchDiagnostic").asText();
                if (Set.of("HUNK_BODY_INVALID", "HUNK_COUNTS_INVALID", "HUNK_HEADER_INVALID", "DIFF_STRUCTURE_INVALID", "INSPECTION_UNAVAILABLE")
                        .contains(diagnostic)) {
                    summary.put("patchDiagnostic", diagnostic);
                }
                String reason = details.path("conflictReason").asText();
                if (Set.of("CHANGE_ALREADY_PRESENT", "INVALID_DIFF", "PATH_STATE_MISMATCH", "CONTEXT_MISMATCH", "CHECK_FAILED").contains(reason)) {
                    summary.put("conflictReason", reason);
                }
            }
            if (AgentTools.mutation(call.name()) && classification.equals("PATCH_NOT_EXECUTED")
                    && details.path("mutationNotStarted").asBoolean()) {
                summary.put("mutationNotStarted", true);
            }
            for (String key : List.of("tests", "executedTests", "skipped", "failures", "errors")) {
                count(summary, details, key);
            }
            if (Set.of("run_checks", "run_frontend_checks").contains(call.name())) {
                String snapshot = details.path("snapshot").asText();
                if (snapshot.matches("[0-9a-f]{64}")) {
                    summary.put("snapshot", snapshot);
                }
                String directory = details.path("directory").asText();
                if (displayPath(directory, true)) {
                    summary.put("directory", directory);
                }
                if (Set.of("npm", "pnpm").contains(details.path("packageManager").asText())) {
                    summary.put("packageManager", details.path("packageManager").asText());
                }
                if (Set.of("NOT_STARTED", "EXECUTED", "UNKNOWN").contains(details.path("executionStatus").asText())) {
                    summary.put("executionStatus", details.path("executionStatus").asText());
                }
                if (Set.of("node-junit", "vitest-json", "node-junit+vitest-json").contains(details.path("runner").asText())) {
                    summary.put("runner", details.path("runner").asText());
                }
                if (details.path("configurationSnapshot").asText().matches("[0-9a-f]{64}")) {
                    summary.put("configurationSnapshot", details.path("configurationSnapshot").asText());
                }
                if (details.path("configurationChanged").isBoolean()) {
                    summary.put("configurationChanged", details.path("configurationChanged").asBoolean());
                }
            }
            // 只取成功应用后受控工具返回的已检查路径，绝不解析 patch 文本。
            JsonNode paths = details.path("paths");
            if (AgentTools.mutation(call.name()) && response.path("ok").asBoolean()
                    && classification.equals("PATCH_APPLIED") && paths.isArray() && paths.size() <= 8) {
                List<String> values = new java.util.ArrayList<>();
                for (JsonNode path : paths) {
                    if (!path.isTextual() || !displayPath(path.asText(), false)) {
                        values.clear();
                        break;
                    }
                    values.add(path.asText());
                }
                if (!values.isEmpty()) {
                    summary.put("paths", values);
                    summary.put("targetState", "available");
                } else if (!paths.isEmpty()) {
                    summary.put("targetState", "withheld");
                }
            }
            return encode(summary);
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Cannot encode tool activity metadata", failure);
        }
    }

    public String failed(AgentProtocol.ToolCall call, String identity, String classification, Long invocationDurationNs) {
        return failed(call, identity, classification, invocationDurationNs, null);
    }

    public String failed(AgentProtocol.ToolCall call, String identity, RuntimeException failure, Long invocationDurationNs) {
        String classification = failure instanceof AgentException classified ? classified.code() : "INTERNAL_FAILURE";
        if (failure instanceof AgentPatchCorrectionException) {
            Map<String, Object> summary = base(call, identity);
            summary.put("ok", false);
            summary.put("resultRecorded", false);
            summary.put("mutationNotStarted", true);
            summary.put("classification", classification);
            return encode(summary);
        }
        return failed(call, identity, classification, invocationDurationNs,
                failure instanceof AgentPolicyRejection rejection ? rejection : null);
    }

    private String failed(AgentProtocol.ToolCall call, String identity, String classification, Long invocationDurationNs,
            AgentPolicyRejection rejection) {
        Map<String, Object> summary = base(call, identity);
        summary.put("ok", false);
        // 未收到完整返回不能证明工具没有产生修改；原异常仍由执行路径终止 Run。
        summary.put("resultRecorded", false);
        if (classification.matches("[A-Z][A-Z0-9_]{0,63}") && unchanged(classification)) {
            summary.put("classification", classification);
        }
        timing(summary, invocationDurationNs);
        if (classification.equals("POLICY_REJECTED")) {
            summary.put("policyRule", rejection == null ? "UNCLASSIFIED_POLICY" : rejection.rule().name());
            summary.put("policyStage", rejection == null ? "TOOL_EXECUTION" : rejection.stage().name());
            if (rejection != null && rejection.rule() == AgentPolicyRejection.Rule.CHECK_MUTATED_SOURCE) {
                sourceChanges(summary, rejection.sourceChanges());
            }
            if (AgentTools.mutation(call.name()) && rejection != null && rejection.sourceEditRejectedBeforeMutation()) {
                summary.put("mutationNotStarted", true);
                if (rejection.rejectedInput() != null) {
                    summary.put("rejectedInput", rejection.rejectedInput());
                }
            }
        }
        return encode(summary);
    }

    private void sourceChanges(Map<String, Object> summary, AgentPolicyRejection.SourceChanges changes) {
        if (changes == null) {
            summary.put("sourceChangesStatus", "UNAVAILABLE");
            return;
        }
        List<AgentPolicyRejection.SourceChange> visible = changes.files().stream()
                .filter(file -> displayPath(file.path(), false)).toList();
        int withheld = changes.files().size() - visible.size();
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("beforeSnapshot", changes.beforeSnapshot());
        diagnostic.put("afterSnapshot", changes.afterSnapshot());
        diagnostic.put("totalChanges", changes.totalChanges());
        diagnostic.put("unexpectedChanges", changes.unexpectedChanges());
        diagnostic.put("files", visible);
        diagnostic.put("withheldFiles", withheld);
        diagnostic.put("truncated", changes.truncated() || withheld > 0);
        summary.put("sourceChangesStatus", changes.truncated() || withheld > 0 ? "PARTIAL" : "RECORDED");
        summary.put("sourceChanges", diagnostic);
    }

    private void proposalLocation(Map<String, Object> summary, JsonNode location) {
        if (!location.isObject() || !Set.of("patch", "new_text").contains(location.path("field").asText())
                || !location.path("lines").isArray() || location.path("lines").isEmpty()
                || location.path("lines").size() > 8) {
            return;
        }
        List<Integer> lines = new ArrayList<>();
        for (JsonNode line : location.path("lines")) {
            if (!line.isIntegralNumber() || !line.canConvertToInt() || line.asInt() < 1 || line.asInt() > 1000000) {
                return;
            }
            lines.add(line.asInt());
        }
        summary.put("rejectedInput", new AgentPolicyRejection.RejectedInput(location.path("field").asText(), lines));
    }

    private void timing(Map<String, Object> summary, Long invocationDurationNs) {
        if (invocationDurationNs != null && invocationDurationNs >= 0) {
            summary.put("invocationDurationNs", invocationDurationNs);
        }
    }

    private Map<String, Object> base(AgentProtocol.ToolCall call, String identity) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metadataVersion", 1);
        summary.put("toolCallId", identity);
        summary.put("tool", call.name());
        try {
            AgentTools.validate(call, limits);
            JsonNode args = json.readTree(call.arguments());
            if (Set.of("list_files", "read_file", "search_code", "edit_file").contains(call.name())) {
                String path = args.path("path").asText(".");
                if (displayPath(path, !Set.of("read_file", "edit_file").contains(call.name()))) {
                    summary.put("path", path);
                } else {
                    summary.put("targetState", "withheld");
                }
            } else if (Set.of("run_checks", "run_frontend_checks").contains(call.name())) {
                summary.put("checkType", call.name().equals("run_frontend_checks") ? "FRONTEND" : "MAVEN");
                summary.put("goal", args.path("goal").asText("test"));
                for (String key : List.of("module", "directory")) {
                    String path = args.path(key).asText(key.equals("directory")
                            ? call.name().equals("run_frontend_checks") ? "" : "." : "");
                    if (displayPath(path, true)) {
                        summary.put(key, path);
                    } else if (!path.isEmpty()) {
                        summary.put("targetState", "withheld");
                    }
                }
            }
        } catch (AgentException | IOException failure) {
            // 参数错误没有可声明的目标，保留关联身份；原执行路径负责拒绝或返回失败。
        }
        return summary;
    }

    private boolean displayPath(String path, boolean allowRoot) {
        if (allowRoot && path.equals(".")) {
            return true;
        }
        // 展示接受沙箱路径规则的保守子集；实际访问仍由沙箱校验符号链接和文件系统边界。
        if (path.isEmpty() || path.length() > 256 || !path.matches("[\\p{L}\\p{N}._/ -]+")
                || path.startsWith("/") || secrets.excludes(path) || !unchanged(path)) {
            return false;
        }
        for (String part : path.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || PROTECTED.contains(lower) || lower.startsWith(".env") || secrets.excludes(part)
                    || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
                return false;
            }
        }
        return true;
    }

    private boolean unchanged(String value) {
        return value.equals(secrets.sanitizeOutput(value));
    }

    private void count(Map<String, Object> summary, JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0) {
            summary.put(key, value.asLong());
        }
    }

    private String encode(Map<String, Object> summary) {
        try {
            return json.writeValueAsString(summary);
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Cannot encode tool activity metadata", failure);
        }
    }
}
