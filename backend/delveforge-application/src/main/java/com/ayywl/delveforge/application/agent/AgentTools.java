package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 五个固定工具的协议。Schema 说明不替代 Application 对实际参数的校验。 */
public final class AgentTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "list_files", Set.of("path", "offset", "limit"),
            "search_code", Set.of("path", "query"),
            "read_file", Set.of("path", "start_line", "line_count"),
            "apply_patch", Set.of("patch"),
            "edit_file", Set.of("path", "old_text", "new_text"),
            "run_checks", Set.of("goal", "directory", "module", "test"));

    private AgentTools() {
    }

    private static AgentProtocol.ToolSpec spec(String name, String description, String properties, String required) {
        return new AgentProtocol.ToolSpec(name, description,
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + properties
                        + "},\"required\":[" + required + "]}");
    }

    public static boolean mutation(String tool) {
        return "apply_patch".equals(tool) || "edit_file".equals(tool);
    }

    public static List<AgentProtocol.ToolSpec> definitions() {
        return List.of(
                spec("list_files", "分页列出当前沙箱文件；默认 path='.'，offset=0，limit=100，最大200。",
                        "\"path\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}", ""),
                spec("search_code", "按字面量搜索当前源码，最多40处结果；不是正则或命令。",
                        "\"path\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"}", "\"query\""),
                spec("read_file", "读取UTF-8文本。默认从第1行读120行，最多300行。净化结果不可作为原文覆盖。",
                        "\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"line_count\":{\"type\":\"integer\"}", "\"path\""),
                spec("edit_file", "优先使用精确文本编辑：path 为相对路径，old_text 必须与当前文件中的一个唯一片段完全一致，new_text 替换该片段。新文件 old_text 为空且必须不存在。不要输出 diff 行数，不要重复已成功的编辑；冲突先重新读取。",
                        "\"path\":{\"type\":\"string\"},\"old_text\":{\"type\":\"string\"},\"new_text\":{\"type\":\"string\"}",
                        "\"path\",\"old_text\",\"new_text\""),
                spec("apply_patch", "提交标准 unified diff（--- a/path、+++ b/path，新增/删除用 /dev/null）。须含正确@@行数；保留源码行尾。冲突后重新读取当前文件，不重复相同补丁；不接受*** Begin Patch。",
                        "\"patch\":{\"type\":\"string\"}", "\"patch\""),
                spec("run_checks", "离线Maven检查。goal可为compile/test/package/verify，默认test；module为相对模块路径，test为测试选择器。环境缺依赖由系统有界准备。",
                        "\"goal\":{\"type\":\"string\",\"enum\":[\"compile\",\"test\",\"package\",\"verify\"]},"
                                + "\"directory\":{\"type\":\"string\",\"description\":\"Maven工程目录，默认仓库根；可选择嵌套reactor\"},"
                                + "\"module\":{\"type\":\"string\"},\"test\":{\"type\":\"string\"}", ""));
    }

    public static void validate(AgentProtocol.ToolCall call, AgentProtocol.Limits limits) {
        if (call.id() == null || call.id().isBlank() || call.id().length() > 200) {
            throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ID_INVALID);
        }
        if (call.name() == null || !FIELDS.containsKey(call.name())) {
            throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_NAME_INVALID);
        }
        if (call.arguments() == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ARGUMENTS_INVALID);
        }
        if (call.arguments().length() > limits.maxPatchBytes() * 2L) {
            throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ARGUMENTS_LIMIT);
        }
        try {
            JsonNode args = JSON.readTree(call.arguments());
            if (args == null || !args.isObject()) {
                throw new AgentException("INVALID_ARGUMENTS", "Tool arguments must be an object");
            }
            var fields = args.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!FIELDS.get(call.name()).contains(field.getKey())
                        || (Set.of("offset", "limit", "start_line", "line_count").contains(field.getKey())
                        ? !field.getValue().isIntegralNumber() : !field.getValue().isTextual())) {
                    throw new AgentException("INVALID_ARGUMENTS", "Unexpected argument or argument type");
                }
            }
            String required = switch (call.name()) {
                case "search_code" -> "query";
                case "read_file", "edit_file" -> "path";
                case "apply_patch" -> "patch";
                default -> null;
            };
            if (required != null && (!args.hasNonNull(required) || args.path(required).asText().isBlank())) {
                throw new AgentException("INVALID_ARGUMENTS", "Missing required tool argument");
            }
            if (call.name().equals("edit_file") && (!args.has("old_text") || !args.has("new_text"))) {
                throw new AgentException("INVALID_ARGUMENTS", "Exact edit requires both source and replacement text");
            }
            if (call.name().equals("run_checks")) {
                if (!Set.of("compile", "test", "package", "verify").contains(args.path("goal").asText("test"))
                        || args.has("module") && !args.path("module").asText().matches("[A-Za-z0-9_./-]{1,200}")
                        || args.has("test") && !args.path("test").asText().matches("[A-Za-z0-9_.$*#,+-]{1,200}")) {
                    throw new AgentPolicyRejection(AgentPolicyRejection.Rule.MAVEN_OPTIONS,
                            AgentPolicyRejection.Stage.ARGUMENTS);
                }
            }
        } catch (IOException failure) {
            throw new AgentException("INVALID_ARGUMENTS", "Invalid JSON tool arguments");
        }
    }
}
