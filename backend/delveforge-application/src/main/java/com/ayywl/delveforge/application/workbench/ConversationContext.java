package com.ayywl.delveforge.application.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentProtocol.Message;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;
import com.ayywl.delveforge.application.workbench.ConversationRetention.Originals;
import com.ayywl.delveforge.application.workbench.Workbench.Context;
import com.ayywl.delveforge.application.workbench.Workbench.Entry;
import com.ayywl.delveforge.application.workbench.Workbench.Run;
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
    private final ConversationRetention retention;

    // 工作台只有一个前台 Run。只记录暂缓重试的位置，进程重启后可再试，检查点仍仅由 SQLite 持有。
    private volatile Deferred deferred;

    private record Deferred(String run, long version, long through, long tokens) {}

    public ConversationContext(
            Workbench store,
            AgentModelPort model,
            AgentTokenCounter counter,
            AgentContextBudget budget) {
        this.store = store;
        this.model = model;
        this.counter = counter;
        this.budget = budget;
        this.retention = new ConversationRetention(store, counter, budget);
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
        需要保持服务时用 setsid -f 并重定向 stdin/stdout/stderr。用 df-workbench history N 读取 [#N] 对应的本对话原始记录，
        用 df-workbench history --offset SEQ 浏览索引，用 df-workbench output INVOCATION --offset BYTE 查询输出。输出缺口表示未捕获。
        不向外发布项目。模型选择行动，平台负责授权、预算与执行事实。
        """
                .formatted(identity.adapter(), identity.endpoint(), identity.requestedModel(),
                        identity.thinking(), run.projectId());
    }

    private Message system(Run run, String summary, long through, Originals originals) {
        var text = new StringBuilder(instructions(run));
        if (through > 0) {
            text.append("\n此前任务检查点（覆盖至 [#").append(through).append("]）\n").append(summary);
        }
        if (originals.newestOmitted() > 0) {
            text.append("\n最近未逐字重放的历史用户消息 [#").append(originals.newestOmitted()).append("]。");
        }
        return Message.text("system", text.toString());
    }

    public List<Message> build(Workbench.Snapshot snapshot) {
        Run run = snapshot.run();
        Context ctx = snapshot.context();
        List<Entry> records = ctx.records();
        var cuts = ConversationRetention.boundaries(records);
        if (cuts.isEmpty()) {
            return pause(run, "历史工具调用与回执未完整配对，无法安全重放。请检查本对话的原始记录。");
        }
        Originals originals = retention.originals(run.conversationId(), ctx.through());
        Message system = system(run, ctx.summary(), ctx.through(), originals);
        var current = ConversationRetention.view(system, originals.messages(), records);
        long before = retention.count(current);
        int minimum = firstReplayableSuffix(records, cuts);
        if (before < budget.triggerTokens() && minimum == 0) {
            return current;
        }

        int unanswered = ConversationRetention.firstUnanswered(records);
        var mandatory = records.subList(unanswered, records.size());
        if (before > budget.usableInputTokens() && !mandatory.isEmpty()
                && retention.count(ConversationRetention.view(
                        Message.text("system", instructions(run)), List.of(), mandatory)) > budget.usableInputTokens()) {
            return pause(run, "最新尚未回应的用户输入连同系统指令和工具超过上下文容量。请缩短输入另开对话，或调大窗口后继续。");
        }

        int lastCut = cuts.indexOf(unanswered);
        long fixed = retention.count(current.subList(0, 1 + originals.messages().size()));
        long tailBudget = Math.min(retention.suffixBudget,
                Math.max(0, retention.target - fixed - retention.originalBudget));
        Outcome outcome = before < budget.triggerTokens() ? Outcome.REPLAY
                : compact(run, ctx, cuts, minimum, lastCut, before, tailBudget);
        if (outcome.reload || !store.currentDecision(run.id())) {
            return List.of();
        }
        if (before <= budget.usableInputTokens() && minimum == 0) {
            return current;
        }
        var fallback = fallback(system, originals, records, cuts, minimum, lastCut);
        if (!fallback.isEmpty()) {
            return fallback;
        }
        return pause(run, outcome.detail + "；完整用户要求、已有任务检查点与历史依据无法同时放入上下文。可调大窗口后继续。");
    }

    private int firstReplayableSuffix(List<Entry> records, List<Integer> cuts) {
        for (int i = records.size() - 1; i >= 0; i--) {
            if (!model.canReplayWithTools(records.get(i).message())) {
                int incompatible = i;
                return ConversationRetention.first(1, cuts.size() - 1, n -> cuts.get(n) > incompatible);
            }
        }
        return 0;
    }

    private enum Outcome {
        PUBLISHED(true, ""), STALE(true, ""), NOT_ADMITTED(true, ""),
        NO_PREFIX(false, "没有可压缩的完整历史前缀"), SUMMARY_LIMIT(false, "摘要请求超过容量"),
        NO_HEADROOM(false, "摘要未腾出足够空间"), DEFERRED(false, "此前压缩未成功，尚无足够的新材料"),
        INVALID(false, "摘要的结构或来源引用无效"), TRUNCATED(false, "摘要响应被截断"),
        FAILED(false, "摘要服务请求失败"), REPLAY(false, "原始消息与当前模型的重放协议不兼容");

        final boolean reload;
        final String detail;

        Outcome(boolean reload, String detail) {
            this.reload = reload;
            this.detail = detail;
        }
    }

    private Outcome compact(Run run, Context ctx, List<Integer> cuts, int minimum, int lastCut,
            long before, long tailBudget) {
        if (lastCut == 0 || minimum > lastCut) {
            return Outcome.NO_PREFIX;
        }
        var records = ctx.records();
        int chosen = ConversationRetention.first(Math.max(1, minimum), lastCut, i ->
                retention.suffixCost(records.subList(cuts.get(i), records.size())) <= tailBudget);
        // 缩短摘要输入只会加长决策尾部。分别预检无工具摘要请求与含工具的决策请求。
        var request = ConversationCheckpoint.request(ctx, records.subList(0, cuts.get(chosen)));
        if (counter.countPromptTokens(request, List.of()) > budget.usableInputTokens()) {
            chosen = ConversationRetention.first(1, chosen, i -> counter.countPromptTokens(
                    ConversationCheckpoint.request(ctx, records.subList(0, cuts.get(i))), List.of())
                    > budget.usableInputTokens()) - 1;
            if (chosen == 0 || chosen < minimum) {
                return Outcome.SUMMARY_LIMIT;
            }
            request = ConversationCheckpoint.request(ctx, records.subList(0, cuts.get(chosen)));
            if (counter.countPromptTokens(request, List.of()) > budget.usableInputTokens()) {
                return Outcome.SUMMARY_LIMIT;
            }
        }
        int cut = cuts.get(chosen);
        long through = records.get(cut - 1).seq();
        if (defer(run, ctx.version(), through, before)) {
            return Outcome.DEFERRED;
        }
        var originals = retention.originals(run.conversationId(), through);
        var suffix = records.subList(cut, records.size());
        long floor = retention.count(ConversationRetention.view(system(run, "", through, originals),
                originals.messages(), suffix));
        if (!enough(before, floor)) {
            return Outcome.NO_HEADROOM;
        }

        AgentProtocol.Reply reply;
        try {
            var admitted = ModelRequests.call(store, model, run.id(), request, List.of());
            if (admitted.isEmpty()) {
                return Outcome.NOT_ADMITTED;
            }
            reply = admitted.get();
        } catch (AgentModelFailure failure) {
            deferred = new Deferred(run.id(), ctx.version(), through, before);
            return failure.reason() == AgentModelFailure.Reason.RESPONSE_TRUNCATED ? Outcome.TRUNCATED : Outcome.FAILED;
        } catch (AgentException failure) {
            if (!"REQUEST_BYTES_LIMIT".equals(failure.code())) {
                throw failure;
            }
            deferred = new Deferred(run.id(), ctx.version(), through, before);
            return Outcome.SUMMARY_LIMIT;
        }

        if (!ConversationCheckpoint.usable(reply, store, run.conversationId(), ctx, records.subList(0, cut))) {
            deferred = new Deferred(run.id(), ctx.version(), through, before);
            return Outcome.INVALID;
        }
        String summary = reply.text().strip();
        long after = retention.count(ConversationRetention.view(system(run, summary, through, originals),
                originals.messages(), suffix));
        if (!enough(before, after)) {
            deferred = new Deferred(run.id(), ctx.version(), through, before);
            return Outcome.NO_HEADROOM;
        }
        if (!store.summarize(run.conversationId(), ctx.version(), through, summary)) {
            return Outcome.STALE;
        }
        deferred = after > retention.target ? new Deferred(run.id(), ctx.version() + 1, through, after) : null;
        return Outcome.PUBLISHED;
    }

    private boolean enough(long before, long after) {
        return after <= retention.target || (before > budget.usableInputTokens() && after <= budget.usableInputTokens()
                && before - after >= budget.triggerTokens() - retention.target);
    }

    private boolean defer(Run run, long version, long through, long before) {
        var last = deferred;
        if (last == null || !last.run().equals(run.id()) || last.version() != version) {
            return false;
        }
        boolean crossedHard = before > budget.usableInputTokens() && last.tokens() <= budget.usableInputTokens();
        long growth = before - last.tokens();
        return through <= last.through() || (!crossedHard && growth < budget.triggerTokens() - retention.target);
    }

    /** 临时视图不推进覆盖位置。没有成功摘要的用户原文全部保留，只有完整执行组可暂由原记录引用代替。 */
    private List<Message> fallback(Message system, Originals originals, List<Entry> records, List<Integer> cuts,
            int minimum, int lastCut) {
        if (lastCut == 0 || minimum > lastCut) {
            return List.of();
        }
        int index = ConversationRetention.first(Math.max(1, minimum), lastCut, i ->
                retention.count(fallbackView(system, originals, records, cuts.get(i))) <= budget.usableInputTokens());
        var view = fallbackView(system, originals, records, cuts.get(index));
        return retention.count(view) <= budget.usableInputTokens() ? view : List.of();
    }

    private List<Message> fallbackView(Message system, Originals originals, List<Entry> records, int cut) {
        var users = new ArrayList<>(originals.messages());
        var prefix = records.subList(0, cut);
        long first = 0;
        long last = 0;
        for (Entry entry : prefix) {
            if ("user".equals(entry.message().role())) {
                users.add(entry.message());
            } else {
                if (first == 0) {
                    first = entry.seq();
                }
                last = entry.seq();
            }
        }
        Message source = first == 0 ? system : Message.text("system", system.text()
                + "\n执行记录暂以历史引用代替（首条 [#" + first + "]，末条 [#" + last
                + "]）。先查看对应回执核实结果、缺口及当前状态，避免重复执行。");
        return ConversationRetention.view(source, users, records.subList(cut, records.size()));
    }

    private List<Message> pause(Run run, String detail) {
        if (store.currentDecision(run.id())) {
            store.state(run.id(), RunState.PAUSED, detail);
        }
        return List.of();
    }
}
