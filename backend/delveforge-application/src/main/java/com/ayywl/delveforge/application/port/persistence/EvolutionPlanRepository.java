package com.ayywl.delveforge.application.port.persistence;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import java.util.Optional;
import java.util.List;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;

/** Planning results are inserted once, as one atomic Aggregate; never overwrite history.
 * Implementations must check the Direction is still SELECTED at commit time.
 * Failure must leave no plan, step, content or evidence rows behind.
 */
public interface EvolutionPlanRepository {
    void save(EvolutionPlan plan);
    Optional<EvolutionPlan> findById(EvolutionPlanId id);
    List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id);
}
