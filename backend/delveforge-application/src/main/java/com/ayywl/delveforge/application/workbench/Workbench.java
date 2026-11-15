package com.ayywl.delveforge.application.workbench;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.domain.workbench.RunState;

import java.util.List;
import java.util.Map;

/** 控制端保存事实；展示模型与提供者专用历史分别读取。 */
public interface Workbench {
    record Run(
            String id,
            String conversationId,
            String projectId,
            RunState state,
            long observed,
            boolean stop) {}

    record Ack(long sequence, String runId, boolean newlyStarted) {}

    record Call(
            String id,
            String runId,
            String providerId,
            String name,
            String arguments,
            String state,
            String container,
            String generation,
            String execId) {}

    record Entry(long seq, AgentProtocol.Message message) {}

    record Context(String summary, long through, long version, List<Entry> records) {}

    record Snapshot(Run run, Context context) {}

    record Output(String stdout, String stderr, long bytes, boolean truncated) {}

    String beginProject(String name, String source);

    void projectResult(String id, String workcopy, String error);

    List<Map<String, Object>> projects();

    String conversation(String project, String name);

    List<Map<String, Object>> conversations(String project);

    Ack receive(String conversation, String requestId, String text, int allowance);

    String start(String conversation, int allowance);

    Run run(String id);

    List<Run> owners();

    Snapshot next(String run);

    boolean requestModel(String run);

    boolean assistant(String run, AgentProtocol.Reply reply);

    List<Call> pending(String run);

    boolean currentDecision(String run);

    boolean prepare(Call call, ExecutionEnvironment.Prepared prepared, String tokenHash);

    boolean admit(Call call);

    void capture(String call, String channel, byte[] bytes);

    Output excerpt(String call, int limit);

    void result(Call call, String text, Integer exit, boolean gap);

    void stop(String run);

    void state(String run, RunState state, String detail);

    void usage(String run, long tokens);

    boolean summarize(String conversation, long expectedVersion, long through, String summary);

    List<String> latestInputs(String conversation, long observed);

    List<Map<String, Object>> timeline(String conversation);

    List<Map<String, Object>> activeTools(String conversation);

    void interruptImports();

    List<Map<String, Object>> runs(String conversation);

    Map<String, Object> lookup(String tokenHash, String kind, String id, int offset, int limit);

    Map<String, Object> output(String conversation, String id, int offset, int limit);
}
