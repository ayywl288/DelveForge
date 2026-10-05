package com.ayywl.delveforge.application.port.persistence;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import java.util.List;

/** Atomic cross-Aggregate lifecycle writes. Domain has already produced each candidate.
 * Implementations must reject stale lifecycle/authorization bases and roll back all writes
 * on failure, including commit failure. Existing content and historical metadata are retained.
 */
public interface EvolutionLifecycleCommitPort {
    void commitActivation(EvolutionPlan activePlan, WorkingCopy readyCopy, SoftwareAsset authorizationBasis);
    void commitDirectionSelection(List<ProductDirectionTransition> directions, List<EvolutionPlanTransition> plans);
}
