package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.domain.agent.Candidate;

/** 正式 Git 仅由系统交付器操作，模型与 Repository Analysis 无此能力。 */
public interface CandidateDeliveryPort {
    enum Position { BASELINE, APPLIED, PUBLISHED, UNKNOWN }

    interface Occupation extends AutoCloseable {
        @Override
        void close();
    }

    Occupation occupy(Delivery delivery);

    Position inspect(Delivery delivery);

    void replay(Delivery delivery, Candidate candidate);

    void apply(Delivery delivery, Candidate candidate);

    String createCommit(Delivery delivery);

    void publish(Delivery delivery);
}
