package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.domain.agent.Candidate;

import java.util.List;

/** 每次转移与对应事件原子持久化，accept 同时提交 Step 与 WorkingCopy 的信任。 */
public interface DeliveryStore {
    Delivery reserve(Candidate candidate, AgentProtocol.Run run, String location, String originalLocation);

    Delivery get(String candidateId);

    List<Delivery> unfinished();

    List<String> pendingCandidates();

    void requireAuthorized(Delivery delivery);

    /** 仅有初始 INTENT 及写入前检查失败；任何已写入、待协调或其他失败历史均不符合。 */
    boolean hasOnlyInitialInspectionFailures(Delivery delivery);

    Delivery advance(Delivery expected, Delivery.State next, String revision, String reason);

    void accept(Delivery delivery);

    /** 幂等补齐历史已接受末次交付的 Plan/WorkingCopy 终结态，不重写交付事实。 */
    void completePlan(Delivery delivery);
}
