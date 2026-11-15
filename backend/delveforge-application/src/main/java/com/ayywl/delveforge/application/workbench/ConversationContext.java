package com.ayywl.delveforge.application.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;
import com.ayywl.delveforge.domain.workbench.RunState;

import java.util.ArrayList;
import java.util.List;

/** 只替换模型请求视图；已配对的原始协议记录及输出始终保留在控制端。 */
public final class ConversationContext {
    public static final List<AgentProtocol.ToolSpec> TOOLS =
            List.of(
                    new AgentProtocol.ToolSpec(
                            "Read",
                            "从 1 起按行分页读取 UTF-8 文件；返回完整文件版本、下一行或 EOF。",
                            schema(
                                    """
                                    "path":{"type":"string"},"start":{"type":"integer","minimum":1},"count":{"type":"integer","minimum":1,"maximum":500}
                                    """,
                                    "\"path\"")),
                    new AgentProtocol.ToolSpec(
                            "Edit",
                            "以唯一精确匹配的 old 原文替换为 new，可保留 CRLF。",
                            schema(
                                    "\"path\":{\"type\":\"string\"},\"old\":{\"type\":\"string\"},\"new\":{\"type\":\"string\"}",
                                    "\"path\",\"old\",\"new\"")),
                    new AgentProtocol.ToolSpec(
                            "Write",
                            "创建新文件；覆盖已有文件必须提供 Read 返回的 expectedVersion。",
                            schema(
                                    "\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"expectedVersion\":{\"type\":\"string\"}",
                                    "\"path\",\"content\"")),
                    new AgentProtocol.ToolSpec(
                            "Bash",
                            "在当前项目根目录启动新的 /bin/bash，可构建、测试、搜索、安装软件、启动服务。",
                            schema("\"command\":{\"type\":\"string\"}", "\"command\"")));
    private final Workbench store;
    private final AgentModelPort model;
    private final AgentTokenCounter counter;
    private final AgentContextBudget budget;

    public ConversationContext(
            Workbench store,
            AgentModelPort model,
            AgentTokenCounter counter,
            AgentContextBudget budget) {
        this.store = store;
        this.model = model;
        this.counter = counter;
        this.budget = budget;
    }

    private static String schema(String properties, String required) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + properties.strip()
                + "},\"required\":["
                + required
                + "]}";
    }

    private String instructions(Workbench.Run run) {
        var identity = model.identity();
        return """
        你是用户个人项目的开发助手，帮助用户完成当前对话的任务。用中文与用户交流。
        控制端当前配置：提供方适配器 %s，服务端点来源 %s，请求模型 ID %s，思考模式 %s。
        回答身份问题时以此配置为准。历史对话中的自称不是配置事实；提供方实际部署的权重和精确版本无法在本地独立核实。
        项目工作副本根目录为 /projects/%s，Linux 工具共享同一文件系统，每次 Bash 都是新 shell。
        这份副本就是用户看到的结果。可直接解释或只读调查；结尾用自然语言陈述结果和真实验证。
        根据当前任务自行选择工具。继续任务前检查相关文件和服务的当前状态，以已记录的退出码及输出缺口为依据，避免重复副作用。
        需要本机 HTTP 预览时先启动实际服务，再在 Bash 运行 df-workbench preview PORT 并向用户给出返回的 URL。
        需要保持服务时用 setsid -f 并重定向 stdin/stdout/stderr。可用 df-workbench history --offset SEQ
        与 df-workbench output INVOCATION --offset BYTE 查询本对话留存的事实；输出缺口表示未捕获。
        不向外发布项目。模型选择行动，平台负责授权、预算与执行事实。
        """
                .formatted(identity.adapter(), identity.endpoint(), identity.requestedModel(),
                        identity.thinking(), run.projectId());
    }

    private List<AgentProtocol.Message> assemble(
            Workbench.Run run, String summary, long through, List<Workbench.Entry> records) {
        var messages = new ArrayList<AgentProtocol.Message>();
        messages.add(
                AgentProtocol.Message.text(
                        "system",
                        instructions(run) + (summary.isBlank() ? "" : "\n此前摘要\n" + summary)));
        // 摘要可遗漏验收条件。只重放已被前缀覆盖的用户原文，保留用户角色和修正顺序。
        if (through > 0) {
            for (String input : store.coveredUserMessages(run.conversationId(), run.id(), through)) {
                messages.add(AgentProtocol.Message.text("user", input));
            }
        }
        records.forEach(r -> messages.add(r.message()));
        return List.copyOf(messages);
    }

    private int boundary(List<Workbench.Entry> records, int wanted) {
        int cut = Math.min(wanted, records.size() - 1);
        while (cut > 0 && records.get(cut).message().role().equals("tool")) {
            cut--;
        }
        return Math.max(0, cut);
    }

    private String material(String previous, List<Workbench.Entry> prefix) {
        var text = new StringBuilder("原摘要\n").append(previous).append("\n已冻结记录\n");
        for (var e : prefix) {
            var m = e.message();
            text.append(e.seq())
                    .append(' ')
                    .append(m.role())
                    .append(' ')
                    .append(m.text())
                    .append('\n');
            for (var c : m.calls()) {
                text.append(c.id())
                        .append(' ')
                        .append(c.name())
                        .append(' ')
                        .append(c.arguments())
                        .append('\n');
            }
            if (m.callId() != null) {
                text.append("工具结果 ").append(m.callId()).append('\n');
            }
        }
        return text.toString();
    }

    public List<AgentProtocol.Message> build(Workbench.Snapshot snapshot) {
        var run = snapshot.run();
        var ctx = snapshot.context();
        var records = ctx.records();
        var current = assemble(run, ctx.summary(), ctx.through(), records);
        long count = counter.countPromptTokens(current, TOOLS);
        if (count < budget.triggerTokens()) {
            return current;
        }

        int cut = boundary(records, records.size() - 6);
        for (int attempt = 0; attempt < 2 && cut > 0; attempt++) {
            var compression =
                    List.of(
                            AgentProtocol.Message.text(
                                    "system",
                                    "按原语言压缩对话记录。保留目标、约束、用户修正、执行结果和缺口、未完成事项、文件与调用 ID。仅输出非空文本摘要。"),
                            AgentProtocol.Message.text(
                                    "user", material(ctx.summary(), records.subList(0, cut))));
            if (counter.countPromptTokens(compression, List.of()) <= budget.usableInputTokens()) {
                try {
                    var admitted =
                            ModelRequests.call(store, model, run.id(), compression, List.of());
                    if (admitted.isEmpty()) {
                        return List.of();
                    }
                    var reply = admitted.get();
                    String summary = reply.text();
                    if (summary != null
                            && !summary.isBlank()
                            && reply.calls().isEmpty()
                            && counter.countPromptTokens(
                                            assemble(
                                                    run,
                                                    summary,
                                                    records.get(cut - 1).seq(),
                                                    records.subList(cut, records.size())),
                                            TOOLS)
                                    < count) {
                        store.summarize(
                                run.conversationId(),
                                ctx.version(),
                                records.get(cut - 1).seq(),
                                summary);
                        return List.of();
                    }
                } catch (AgentModelFailure failure) {
                    if (failure.reason() == AgentModelFailure.Reason.PROVIDER_CALL_FAILED) {
                        break;
                    }
                    // 截断或无效摘要可用较小的完整前缀再试一次。
                } catch (AgentException failure) {
                    if (!failure.code().equals("REQUEST_BYTES_LIMIT")) {
                        break;
                    }
                } catch (RuntimeException failure) {
                    break;
                }
            }
            cut = boundary(records, cut / 2);
        }
        if (count <= budget.usableInputTokens()) {
            return current;
        }

        // assemble 已按用户角色加入冻结前缀的原文。回退提示只说明丢失的执行细节。
        String minimum = "有损上下文。此前执行细节请用 df-workbench history 查询，避免重跑。";
        var candidates =
                ctx.summary().isEmpty()
                        ? List.of(minimum)
                        : List.of(minimum + "\n原有效摘要\n" + ctx.summary(), minimum);
        for (String reduced : candidates) {
            for (int recent = 4; recent >= 1; recent--) {
                cut = boundary(records, records.size() - recent);
                if (cut > 0
                        && counter.countPromptTokens(
                                        assemble(
                                                run, reduced, records.get(cut - 1).seq(),
                                                records.subList(cut, records.size())),
                                        TOOLS)
                                <= budget.usableInputTokens()) {
                    store.summarize(
                            run.conversationId(),
                            ctx.version(),
                            records.get(cut - 1).seq(),
                            reduced);
                    return List.of();
                }
            }
        }
        var withoutOldSummary = assemble(run, minimum, ctx.through(), records);
        if (counter.countPromptTokens(withoutOldSummary, TOOLS) <= budget.usableInputTokens()) {
            return withoutOldSummary;
        }
        store.state(run.id(), RunState.PAUSED, "完整指令、用户原文与成对工具记录超过上下文容量。可在新对话中缩短任务说明，或调大上下文后继续。");
        return List.of();
    }
}
