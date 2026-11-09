package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;

import java.util.List;

/** reserve 与 freeze 是原子边界；任何写失败不得伪造成功，保留占用直到资源已停止。 */
public interface AgentStore {
    void reserve(AgentProtocol.Run run);

    AgentProtocol.Run get(String id);

    default List<AgentProtocol.Run> runs(String planId, String stepId, String before, int limit) {
        throw new UnsupportedOperationException("Run history is unavailable");
    }

    List<AgentProtocol.Run> abandoned();

    /** 进入 RUNNING / FINALIZING 时原子保存保留意图；失败终态保持，确认 NO_CHANGES 时才可解除。 */
    void advance(String id, AgentRunState expected, AgentRunState next, String reason);

    void event(String id, String kind, String sanitizedSummary);

    List<AgentProtocol.Event> events(String id, long after, int limit);

    void usage(String id, int modelCalls, int toolCalls, Long tokens);

    void requestCancel(String id);

    /** Candidate 与 SUCCEEDED 原子冻结后才解除沙箱材料保留。 */
    void freeze(Candidate candidate);

    Candidate candidate(String id);

    /** 调用方必须先证实资源停止；释放占用不能清除既有的持久化保留意图。 */
    void release(String id, boolean retainMaterial);
}
