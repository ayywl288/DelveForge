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

    Delivery advance(Delivery expected, Delivery.State next, String revision, String reason);

    void accept(Delivery delivery);
}
