package com.ayywl.delveforge.application.evolution.planning;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import java.util.Objects;
public final class GetEvolutionPlanUseCase {
    private final EvolutionPlanRepository repository;
    public GetEvolutionPlanUseCase(EvolutionPlanRepository repository) { this.repository = Objects.requireNonNull(repository); }
    public EvolutionPlan get(EvolutionPlanId id) {
        if (id == null) throw new IllegalArgumentException("Plan id is required");
        return repository.findById(id).orElseThrow(() -> new EvolutionPlanNotFoundException(id));
    }
}
