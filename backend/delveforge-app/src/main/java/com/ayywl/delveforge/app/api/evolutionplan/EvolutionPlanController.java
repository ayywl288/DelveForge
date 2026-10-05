package com.ayywl.delveforge.app.api.evolutionplan;
import com.ayywl.delveforge.application.evolution.planning.GenerateEvolutionPlanRequest;
import com.ayywl.delveforge.application.evolution.planning.GenerateEvolutionPlanUseCase;
import com.ayywl.delveforge.application.evolution.planning.GetEvolutionPlanUseCase;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Interface mapping only. Planning neither activates the Plan nor authorizes steps. */
@RestController
@RequestMapping("/api/evolution-plans")
public class EvolutionPlanController {
    private final GenerateEvolutionPlanUseCase generate;
    private final GetEvolutionPlanUseCase get;
    public EvolutionPlanController(GenerateEvolutionPlanUseCase generate, GetEvolutionPlanUseCase get) {
        this.generate = generate;
        this.get = get;
    }
    @PostMapping("/planning")
    @ResponseStatus(HttpStatus.CREATED)
    public EvolutionPlanResponse plan(@RequestBody EvolutionPlanningRequest request) {
        return EvolutionPlanResponse.from(generate.generate(new GenerateEvolutionPlanRequest(
                new ProductDirectionId(request.productDirectionId()), new SoftwareAssetId(request.baseAssetId()),
                new RepositoryProfileId(request.baseRepositoryProfileId()))));
    }
    @GetMapping("/{id}")
    public EvolutionPlanResponse get(@PathVariable String id) {
        return EvolutionPlanResponse.from(get.get(new EvolutionPlanId(id)));
    }
}
