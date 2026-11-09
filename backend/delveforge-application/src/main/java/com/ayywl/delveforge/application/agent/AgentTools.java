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
            "exec_command", Set.of("command", "directory", "timeout_seconds"),
            "update_plan", Set.of("tasks"),
            "read_log", Set.of("invocation_id", "stream", "offset", "limit"));

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
        return definitions(AgentProtocol.Limits.defaults());
    }

    public static List<AgentProtocol.ToolSpec> definitions(AgentProtocol.Limits limits) {
        return List.of(
                spec("list_files", "分页列出文件的相对路径 path 和字节数 sizeBytes；默认 path='.'，offset=0，limit=100，最大200。",
                        "\"path\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}", ""),
                spec("search_code", "按区分大小写的字面量搜索源码，支持大文件。最多返回40个匹配行，每行摘录最多400个字符。",
                        "\"path\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"}", "\"query\""),
                spec("read_file", "读取预算内的完整 UTF-8 源码行，默认从第1行读120行，最多300行。hasMore 为真时从 nextStartLine 续读。返回原文，可用于精确编辑；不要将命令日志中的脱敏值写回。",
                        "\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"line_count\":{\"type\":\"integer\"}", "\"path\""),
                spec("edit_file", "优先使用精确文本编辑：path 为相对路径，old_text 必须与当前文件中的一个唯一片段完全一致，new_text 替换该片段。新文件 old_text 为空且必须不存在。不要输出 diff 行数，不要重复已成功的编辑；冲突先重新读取当前文件，做一次有依据的重试。批量转换、格式化或完整生成文件可使用 Shell，不要盲目重复冲突。",
                        "\"path\":{\"type\":\"string\"},\"old_text\":{\"type\":\"string\"},\"new_text\":{\"type\":\"string\"}",
                        "\"path\",\"old_text\",\"new_text\""),
                spec("update_plan", "可选的当前工作清单；tasks 每项含 title 和 pending/in_progress/completed 状态。"
                        + "更新替换整个清单，不修改源码、Step 授权或完成判定；完成标记只是 Agent 判断。最多20项。"
                        + "若本次维护过清单，收尾时按实际结果更新。只将有证据的事项标为完成，保留未完成事项，受阻时写明原因。",
                        "\"tasks\":{\"type\":\"array\",\"maxItems\":20,\"items\":{\"type\":\"object\","
                                + "\"additionalProperties\":false,\"properties\":{\"title\":{\"type\":\"string\"},"
                                + "\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\"]}},"
                                + "\"required\":[\"title\",\"status\"]}}", "\"tasks\""),
                spec("read_log", "按 exec_command 返回的 invocationId 读取已净化日志；stream 为 stdout/stderr/command，offset 为字符偏移，limit 默认4000最多8000。日志不在源码内，Run 清理后仍可读取；返回 nextOffset 和丢弃标记。",
                        "\"invocation_id\":{\"type\":\"string\"},\"stream\":{\"type\":\"string\",\"enum\":[\"stdout\",\"stderr\",\"command\"]},"
                                + "\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}", "\"invocation_id\""),
                spec("exec_command", "在隔离工作区用 /bin/sh 执行原生命令；directory 为相对 cwd，默认根目录 .。源码、项目依赖、/m2 缓存跨调用保留；cwd、环境变量、进程、/tmp 不保留。原始退出码遵循 Shell 管道语义，不等于测试通过。优先直接输出；平台返回预览及可用 read_log 翻页的日志，勿往源码写临时日志。单流预览最多3000字符，保留日志受单次和每Run总量限制；超额明确标记。",
                        "\"command\":{\"type\":\"string\"},\"directory\":{\"type\":\"string\"},"
                                + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":" + limits.checkSeconds() + "}", "\"command\""));
    }

    public enum CommandError {
        INVALID_ARGUMENTS("核对字段名称、类型和必填 command；安装命令不需要验证字段。"),
        INVALID_COMMAND("command 必须是非空文本，不超过 16000 字符且不能包含 NUL。"),
        INVALID_TIMEOUT("timeout_seconds 必须是运行时限内的正整数；也可以省略，使用本次运行上限。"),
        INVALID_DIRECTORY("directory 必须是现有的仓库相对目录；根目录用 .，先用 list_files 核对路径。"),
        DIRECTORY_NOT_FOUND("工作目录不存在或不是目录。先用 list_files 查找；命令中的相对路径从该目录计算，不要重复目录前缀。");

        private final String instruction;

        CommandError(String instruction) {
            this.instruction = instruction;
        }
    }

    private static final class CommandArgumentException extends AgentException {
        private final CommandError reason;

        private CommandArgumentException(CommandError reason) {
            super("INVALID_ARGUMENTS", reason.instruction);
            this.reason = reason;
        }
    }

    /** 只发送固定诊断，不回显未经校验的参数或异常正文。 */
    public static String commandError(CommandError reason, AgentProtocol.Limits limits) {
        String instruction = reason.instruction;
        if (reason == CommandError.INVALID_TIMEOUT) {
            instruction += "本次上限为 " + limits.checkSeconds() + " 秒。";
        }
        return JSON.createObjectNode().put("ok", false).put("classification", "INVALID_ARGUMENTS")
                .put("reason", reason.name()).put("mutationNotStarted", true)
                .put("instruction", instruction).toString();
    }

    public static String argumentError(AgentProtocol.ToolCall call, AgentException failure, AgentProtocol.Limits limits) {
        if (readOnly(call.name())) {
            return readError(ReadError.INVALID_ARGUMENTS);
        }
        if (call.name().equals("exec_command")) {
            return commandError(failure instanceof CommandArgumentException command
                    ? command.reason : CommandError.INVALID_ARGUMENTS, limits);
        }
        return "{\"ok\":false,\"classification\":\"INVALID_ARGUMENTS\"}";
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
                        ? !field.getValue().isIntegralNumber() : field.getKey().equals("tasks")
                            ? !field.getValue().isArray() : !field.getValue().isTextual())) {
                    throw new AgentException("INVALID_ARGUMENTS", "Unexpected argument or argument type");
                }
            }
            String required = switch (call.name()) {
                case "exec_command" -> "command";
                case "search_code" -> "query";
                case "update_plan" -> null;
                case "read_log" -> "invocation_id";
                case "read_file", "edit_file" -> "path";
                default -> null;
            };
            if (required != null && (!args.hasNonNull(required) || args.path(required).asText().isBlank())) {
                throw new AgentException("INVALID_ARGUMENTS", "Missing required tool argument");
            }
            if (call.name().equals("update_plan")) {
                JsonNode tasks = args.path("tasks");
                if (!tasks.isArray() || tasks.size() > 20
                        || args.toString().length() > Math.min(4000, limits.maxContextChars() / 4)) {
                    throw new AgentException("INVALID_ARGUMENTS", "Working checklist exceeds this context budget");
                }
                for (JsonNode task : tasks) {
                    if (!task.isObject() || task.size() != 2 || !task.path("title").isTextual()
                            || task.path("title").asText().isBlank() || task.path("title").asText().length() > 200
                            || !Set.of("pending", "in_progress", "completed").contains(task.path("status").asText())) {
                        throw new AgentException("INVALID_ARGUMENTS", "Invalid checklist task");
                    }
                }
            }
            if (call.name().equals("read_log") && (!args.path("invocation_id").asText().matches("[0-9a-f-]{36}")
                    || !Set.of("stdout", "stderr", "command").contains(args.path("stream").asText("stdout"))
                    || args.has("offset") && (!args.path("offset").canConvertToInt() || args.path("offset").asInt() < 0)
                    || args.has("limit") && (!args.path("limit").canConvertToInt()
                        || args.path("limit").asInt() < 1 || args.path("limit").asInt() > 8000))) {
                throw new AgentException("INVALID_ARGUMENTS", "Invalid log identity or range");
            }
            if (call.name().equals("edit_file") && (!args.has("old_text") || !args.has("new_text"))) {
                throw new AgentException("INVALID_ARGUMENTS", "Exact edit requires both source and replacement text");
            }
            if (call.name().equals("exec_command")) {
                if (args.path("command").asText().length() > 16000
                        || args.path("command").asText().indexOf(0) >= 0) {
                    throw new CommandArgumentException(CommandError.INVALID_COMMAND);
                }
                if (args.has("directory") && args.path("directory").asText().isBlank()) {
                    throw new CommandArgumentException(CommandError.INVALID_DIRECTORY);
                }
                if (args.has("timeout_seconds") && (!args.path("timeout_seconds").canConvertToInt()
                            || args.path("timeout_seconds").asInt() < 1
                            || args.path("timeout_seconds").asInt() > limits.checkSeconds())) {
                    throw new CommandArgumentException(CommandError.INVALID_TIMEOUT);
                }

            }
        } catch (IOException failure) {
            throw new AgentException("INVALID_ARGUMENTS", "Invalid JSON tool arguments");
        }
    }
}
