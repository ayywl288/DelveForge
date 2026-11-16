package com.ayywl.delveforge.application.workbench;

import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 前台准入、纠正消息、工具配对与恢复走同一控制端存储。模型和外部命令均在事务之外。 */
public final class ConversationAgent implements AutoCloseable {
    private final Workbench store;
    private final ProjectFiles files;
    private final ExecutionEnvironment environment;
    private final AgentModelPort model;
    private final ConversationContext context;
    private final ObjectMapper json;
    private final int allowance;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    public ConversationAgent(
            Workbench store,
            ProjectFiles files,
            ExecutionEnvironment environment,
            AgentModelPort model,
            ConversationContext context,
            ObjectMapper json,
            int allowance) {
        this.store = store;
        this.files = files;
        this.environment = environment;
        this.model = model;
        this.context = context;
        this.json = json;
        this.allowance = requestLimit(allowance);
    }

    public String project(String name, String source) {
        String id = store.beginProject(required(name, 160), source);
        try {
            store.projectResult(id, files.prepare(id, source), null);
        } catch (RuntimeException e) {
            store.projectResult(id, null, e.getMessage());
            throw e;
        }
        return id;
    }

    public static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new WorkbenchRejection(WorkbenchRejection.Kind.INVALID, "内容为空或超出长度上限");
        }
        return value;
    }

    public Workbench.Ack receive(String conversation, String request, String text) {
        return receive(conversation, request, text, null);
    }

    public int defaultRequestLimit() {
        return allowance;
    }

    private static int requestLimit(int limit) {
        if (limit < 1 || limit > 10000) {
            throw new WorkbenchRejection(WorkbenchRejection.Kind.INVALID, "任务请求总上限须在 1 到 10000 之间");
        }
        return limit;
    }

    public Workbench.Ack receive(String conversation, String request, String text, Integer limit) {
        var ack =
                store.receive(
                        conversation, required(request, 120), required(text, 64000),
                        allowance, limit == null ? null : requestLimit(limit));
        if (ack.newlyStarted()) {
            schedule(ack.runId(), false);
        }
        dispatch();
        return ack;
    }

    public String start(String conversation) {
        return start(conversation, null);
    }

    public String start(String conversation, Integer limit) {
        String id = store.start(conversation, allowance, limit == null ? null : requestLimit(limit));
        if (store.run(id).state() == RunState.RUNNING) {
            schedule(id, false);
        }
        dispatch();
        return id;
    }

    public void stop(String id) {
        store.stop(id);
        var state = store.run(id).state();
        if (!state.ownsForeground()) {
            return;
        }
        if (state == RunState.BLOCKED) {
            recover(id);
            return;
        }
        // 仅封存已准入的调用。工作线程负责在所有前置调用后提交配对结果。
        workers.execute(
                () -> {
                    for (var call : store.pending(id)) {
                        if (call.state().equals("ADMITTED")) {
                            try {
                                environment.reconcile(call, true);
                            } catch (RuntimeException failure) {
                                store.state(
                                        id, RunState.BLOCKED, "停止请求已保存，前台执行仍待核对：" + safe(failure));
                            }
                        }
                    }
                });
    }

    public void recover(String id) {
        if (!store.run(id).state().ownsForeground()) {
            return;
        }
        store.state(id, RunState.RECONCILING, "正在核对原前台执行");
        schedule(id, true);
    }

    public void startup() {
        store.interruptImports();
        store.restoreQueuedInputs(allowance);
        for (var run : store.owners()) {
            if (store.unstarted(run.id())) {
                schedule(run.id(), false);
            } else {
                recover(run.id());
            }
        }
        dispatch();
    }

    private void dispatch() {
        while (!workers.isShutdown()) {
            Optional<String> next = store.claimNext();
            if (next.isEmpty()) {
                return;
            }
            schedule(next.get(), false);
        }
    }

    public synchronized void maintain(String project, String action) {
        if (action.equals("Check")) {
            if (store.maintenance(project).isEmpty() || !environment.maintenanceEnded(project)) {
                throw new WorkbenchRejection(WorkbenchRejection.Kind.BUSY, "尚不能确认项目维护已经结束");
            }
        } else {
            if (!action.equals("Restart") && !action.equals("Rebuild")) {
                throw new WorkbenchRejection(WorkbenchRejection.Kind.INVALID, "请选择重启或重建项目环境");
            }
            store.lockEnvironment(project, action);
            try {
                environment.maintain(project, action.equals("Rebuild"));
            } catch (RuntimeException failure) {
                if (environment.maintenanceEnded(project)) {
                    store.unlockEnvironment(project);
                    dispatch();
                }
                throw failure;
            }
        }
        store.unlockEnvironment(project);
        store.owners().stream().filter(run -> run.projectId().equals(project) && run.state() == RunState.BLOCKED)
                .forEach(run -> recover(run.id()));
        dispatch();
    }

    public Optional<String> chooseSourceFolder() {
        return files.chooseSourceFolder();
    }

    public void open(String project) {
        files.open(project);
    }

    private void schedule(String id, boolean recovering) {
        if (scheduled.add(id)) {
            workers.execute(
                    () -> {
                        try {
                            if (recovering) {
                                reconcileRun(id);
                            } else {
                                loop(id);
                            }
                        } catch (RuntimeException e) {
                            boolean admitted =
                                    store.pending(id).stream()
                                            .anyMatch(c -> c.state().equals("ADMITTED"));
                            if (admitted) {
                                store.state(id, RunState.BLOCKED, "前台执行待核对：" + safe(e));
                            } else {
                                for (var c : store.pending(id)) {
                                    skip(c, "控制端中断，未执行");
                                }
                                store.state(
                                        id,
                                        store.run(id).stop() ? RunState.STOPPED : RunState.PAUSED,
                                        safe(e));
                            }
                        } finally {
                            scheduled.remove(id);
                            // “继续”可能恰好发生在旧工作线程准备退出时；补上已提交的新调度。
                            if (!workers.isShutdown()) {
                                var state = store.run(id).state();
                                if (state == RunState.RUNNING || state == RunState.RECONCILING) {
                                    schedule(id, state == RunState.RECONCILING);
                                } else if (state != RunState.PAUSED && state != RunState.BLOCKED) {
                                    context.forget(id);
                                }
                                dispatch();
                            }
                        }
                    });
        }
    }

    private static String safe(RuntimeException e) {
        // 外部异常不转发 HTTP 请求体、响应或原始命令。
        if (e instanceof AgentModelFailure failure) {
            return ModelRequests.describe(failure);
        }
        if (e instanceof com.ayywl.delveforge.application.agent.AgentException a) {
            return a.code();
        }
        return "控制操作失败（" + e.getClass().getSimpleName() + "）";
    }

    private void loop(String id) {
        while (!Thread.currentThread().isInterrupted()) {
            var run = store.run(id);
            if (run.stop()) {
                reconcileRun(id);
                return;
            }
            if (run.state() != RunState.RUNNING) {
                return;
            }
            var calls = store.pending(id);
            if (!calls.isEmpty()) {
                if (!tool(run, calls.getFirst())) {
                    return;
                }
                continue;
            }
            var snapshot = store.next(id);
            var messages = context.build(snapshot);
            if (messages.isEmpty()) {
                continue;
            }
            var admitted =
                    ModelRequests.call(store, model, id, messages, ConversationContext.TOOLS);
            if (admitted.isEmpty()) {
                continue;
            }
            var reply = admitted.get();
            validate(reply);
            store.assistant(id, reply);
        }
    }

    private void validate(AgentProtocol.Reply reply) {
        var ids = new HashSet<String>();
        if (reply.calls().isEmpty() && (reply.text() == null || reply.text().isBlank())) {
            throw new IllegalArgumentException("模型响应为空");
        }
        for (var c : reply.calls()) {
            if (c.id() == null
                    || c.id().isBlank()
                    || !ids.add(c.id())
                    || c.name() == null
                    || c.arguments() == null) {
                throw new IllegalArgumentException("模型工具协议不完整");
            }
        }
    }

    private boolean tool(Workbench.Run run, Workbench.Call call) {
        if (!call.state().equals("PROPOSED")) {
            reconcileRun(run.id());
            return false;
        }
        try {
            var a = json.readTree(call.arguments());
            if (!a.isObject()
                    || !List.of("Read", "Edit", "Write", "Bash").contains(call.name())
                    || call.name().equals("Bash") && !a.path("command").isTextual()) {
                skip(call, "工具或参数无效，未执行");
                return true;
            }
        } catch (JsonProcessingException e) {
            skip(call, "参数 JSON 无效，未执行");
            return true;
        }

        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        if (!store.currentDecision(run.id())) {
            skip(call, "新输入或停止请求先到，此调用未开始");
            return true;
        }
        var prepared = environment.prepare(run, call, token);
        if (!store.prepare(call, prepared, hash(token)) || !store.admit(call)) {
            skip(call, "新输入或停止请求先到，此调用未开始");
            return true;
        }
        var active = store.pending(run.id()).getFirst();
        boolean gap = false;
        boolean timeout = false;
        try {
            environment.execute(
                    active, (stream, bytes) -> store.capture(active.id(), stream, bytes));
        } catch (ExecutionEnvironment.TimedOut expired) {
            gap = true;
            timeout = true;
        } catch (RuntimeException lost) {
            gap = true;
        }
        return finish(active, gap, timeout);
    }

    private boolean finish(Workbench.Call call, boolean gap, boolean timeout) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var proof = environment.reconcile(call, true);
                if (proof.ended()) {
                    var bytes = store.excerpt(call.id(), 24000);
                    var facts = new LinkedHashMap<String, Object>();
                    facts.put("invocation", call.id());
                    facts.put("exit", proof.exit());
                    facts.put("stdout", bytes.stdout());
                    facts.put("stderr", bytes.stderr());
                    facts.put("capturedBytes", bytes.bytes());
                    facts.put("displayTruncated", bytes.truncated());
                    facts.put("outputGap", gap);
                    facts.put("detail", proof.detail());
                    facts.put("timedOut", timeout);
                    facts.put("stopRequested", store.run(call.runId()).stop());
                    if (gap || proof.exit() == null) {
                        facts.put("next", "先检查当前文件及服务，再决定进一步操作");
                    }
                    store.result(call, serialize(facts), proof.exit(), gap);
                    return true;
                }
            } catch (RuntimeException e) {
                if (attempt == 2) {
                    break;
                }
            }
            try {
                Thread.sleep(200L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        store.state(call.runId(), RunState.BLOCKED, "无法证明前台进程已结束。恢复 Docker 后重新核对，必要时显式重启该项目环境。");
        return false;
    }

    private void reconcileRun(String id) {
        store.state(id, RunState.RECONCILING, "正在核对原前台执行");
        for (var c : store.pending(id)) {
            if (c.state().equals("ADMITTED")) {
                if (!finish(c, true, false)) {
                    return;
                }
            } else {
                skip(c, "控制端中断或用户停止，调用未开始");
            }
        }
        boolean stopped = store.run(id).stop();
        store.state(
                id,
                stopped ? RunState.STOPPED : RunState.PAUSED,
                stopped ? "前台已结束，已完成的修改保留" : "已核对前台执行，继续时将使用实际工作副本和留存记录");
    }

    private void skip(Workbench.Call call, String detail) {
        store.result(
                call,
                serialize(
                        java.util.Map.of(
                                "invocation", call.id(), "notStarted", true, "detail", detail)),
                null,
                false);
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具收据编码失败", e);
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
