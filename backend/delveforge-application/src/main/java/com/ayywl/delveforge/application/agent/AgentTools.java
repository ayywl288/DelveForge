package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定工具的协议。Schema 说明不替代 Application 对实际参数的校验。 */
public final class AgentTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "list_files", Set.of("path", "offset", "limit"),
            "search_code", Set.of("path", "query"),
            "read_file", Set.of("path", "start_line", "line_count"),
            "edit_file", Set.of("path", "old_text", "new_text"),
            "exec_command", Set.of("command", "directory", "timeout_seconds", "verification_scope", "check_kind"));

    private AgentTools() {
    }

    private static AgentProtocol.ToolSpec spec(String name, String description, String properties, String required) {
        return new AgentProtocol.ToolSpec(name, description,
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + properties
                        + "},\"required\":[" + required + "]}");
    }

    public static boolean mutation(String tool) {
        return "exec_command".equals(tool) || "edit_file".equals(tool);
    }

    public static boolean readOnly(String tool) {
        return Set.of("read_file", "list_files", "search_code").contains(tool);
    }

    /** 只用于已证明未读取内容、未写入的普通输入错误；不转换安全拒绝。 */
    public enum ReadError {
        RELATIVE_PATH_REQUIRED("使用仓库相对路径和 / 分隔符；位置不确定时先用 list_files 查找。"),
        PATH_NOT_FOUND("该源码路径不存在；先用 list_files 查找，再使用返回的准确路径。"),
        INVALID_RANGE("start_line 必须为正整数，line_count 必须为 1 到 300 的整数。"),
        INVALID_PAGINATION("offset 必须为非负整数，limit 必须为 1 到 200 的整数。"),
        INVALID_QUERY("query 必须为 1 到 200 个字符的字面量。"),
        INVALID_ARGUMENTS("核对工具 schema 的必填字段和类型；路径使用仓库相对路径和 / 分隔符。");

        private final String instruction;

        ReadError(String instruction) {
            this.instruction = instruction;
        }

        public String instruction() {
            return instruction;
        }
    }

    public static String readError(ReadError reason) {
        return JSON.createObjectNode().put("ok", false).set("result", JSON.createObjectNode()
                .put("classification", "INVALID_TOOL_ARGUMENT").put("reason", reason.name())
                .put("readNotStarted", true).put("mutationNotStarted", true)
                .put("instruction", reason.instruction())).toString();
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
                spec("exec_command", "在隔离工作区执行仓库原生命令；支持 shell 组合和文件删除/重命名。directory 为仓库相对目录，默认 .。文件与依赖保留，但 shell 变量和 cwd 不跨调用保留。验证命令须同时提供 verification_scope（当前 Step/仓库要求）与 check_kind（test/build/other）；已登记的验证命令必须在最终源码上通过。",
                        "\"command\":{\"type\":\"string\"},\"directory\":{\"type\":\"string\"},"
                                + "\"timeout_seconds\":{\"type\":\"integer\"},\"verification_scope\":{\"type\":\"string\"},"
                                + "\"check_kind\":{\"type\":\"string\",\"enum\":[\"test\",\"build\",\"other\"]}", "\"command\""));
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
                        || (Set.of("offset", "limit", "start_line", "line_count", "timeout_seconds").contains(field.getKey())
                        ? !field.getValue().isIntegralNumber() : !field.getValue().isTextual())) {
                    throw new AgentException("INVALID_ARGUMENTS", "Unexpected argument or argument type");
                }
            }
            String required = switch (call.name()) {
                case "exec_command" -> "command";
                case "search_code" -> "query";
                case "read_file", "edit_file" -> "path";
                default -> null;
            };
            if (required != null && (!args.hasNonNull(required) || args.path(required).asText().isBlank())) {
                throw new AgentException("INVALID_ARGUMENTS", "Missing required tool argument");
            }
            if (call.name().equals("edit_file") && (!args.has("old_text") || !args.has("new_text"))) {
                throw new AgentException("INVALID_ARGUMENTS", "Exact edit requires both source and replacement text");
            }
            if (call.name().equals("exec_command")) {
                if (args.path("command").asText().length() > 16000
                        || args.path("command").asText().indexOf(0) >= 0
                        || args.has("timeout_seconds") && (!args.path("timeout_seconds").canConvertToInt()
                            || args.path("timeout_seconds").asInt() < 1
                            || args.path("timeout_seconds").asInt() > limits.checkSeconds())
                        || args.has("verification_scope") != args.has("check_kind")
                        || args.has("verification_scope") && (args.path("verification_scope").asText().isBlank()
                            || args.path("verification_scope").asText().length() > 1000
                            || !Set.of("test", "build", "other").contains(args.path("check_kind").asText()))) {
                    throw new AgentException("INVALID_ARGUMENTS", "Invalid command, timeout or verification scope");
                }
            }
        } catch (IOException failure) {
            throw new AgentException("INVALID_ARGUMENTS", "Invalid JSON tool arguments");
        }
    }
}
