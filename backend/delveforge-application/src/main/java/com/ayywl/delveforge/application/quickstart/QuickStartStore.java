package com.ayywl.delveforge.application.quickstart;

import java.util.Optional;

/** Application 拥有 Plan 级产物及最终交付读取合同，基础设施核对持久化事实。 */
public interface QuickStartStore {
    record Basis(String planId, String candidateId, String revision, String location, String repositoryLocation) {
        public Basis(String planId, String candidateId, String revision, String location) {
            this(planId, candidateId, revision, location, location);
        }
    }

    Optional<Basis> finalDelivery(String planId);
    Optional<QuickStart> find(String planId);
    void save(QuickStart guide);
    void interruptGenerating();
}
