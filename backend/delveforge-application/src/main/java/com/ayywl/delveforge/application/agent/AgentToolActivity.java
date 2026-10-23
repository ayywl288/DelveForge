package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
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
            count(summary, response, "durationMs");
            for (String key : List.of("tests", "executedTests", "skipped", "failures", "errors")) {
                count(summary, details, key);
            }
            // 只取成功应用后受控工具返回的已检查路径，绝不解析 patch 文本。
            JsonNode paths = details.path("paths");
            if (call.name().equals("apply_patch") && response.path("ok").asBoolean()
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
        Map<String, Object> summary = base(call, identity);
        summary.put("ok", false);
        // 未收到完整返回不能证明工具没有产生修改；原异常仍由执行路径终止 Run。
        summary.put("resultRecorded", false);
        if (classification.matches("[A-Z][A-Z0-9_]{0,63}") && unchanged(classification)) {
            summary.put("classification", classification);
        }
        timing(summary, invocationDurationNs);
        return encode(summary);
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
            if (Set.of("list_files", "read_file", "search_code").contains(call.name())) {
                String path = args.path("path").asText(".");
                if (displayPath(path, !call.name().equals("read_file"))) {
                    summary.put("path", path);
                } else {
                    summary.put("targetState", "withheld");
                }
            } else if (call.name().equals("run_checks")) {
                summary.put("goal", args.path("goal").asText("test"));
                for (String key : List.of("module", "directory")) {
                    String path = args.path(key).asText(key.equals("directory") ? "." : "");
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
        return value.equals(secrets.sanitize(List.of(new RepositorySourceFile("agent-activity.txt", value)))
                .material().getFirst().content());
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
