package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.evolution.planning.GenerateEvolutionPlanRequest;
import com.ayywl.delveforge.application.evolution.planning.GenerateEvolutionPlanUseCase;
import com.ayywl.delveforge.application.evolution.planning.GetEvolutionPlanUseCase;
import com.ayywl.delveforge.application.evolution.provisioning.PrepareEvolutionPlanUseCase;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 接口层只转换请求与响应；生成 EvolutionPlan 不会激活计划或授权 EvolutionStep。
 */
@RestController
@RequestMapping("/api/evolution-plans")
public class EvolutionPlanController {
    private final GenerateEvolutionPlanUseCase generate;
    private final GetEvolutionPlanUseCase get;
    private final PrepareEvolutionPlanUseCase prepare;

    public EvolutionPlanController(GenerateEvolutionPlanUseCase generate, GetEvolutionPlanUseCase get,
            PrepareEvolutionPlanUseCase prepare) {
        this.generate = generate;
        this.get = get;
        this.prepare = prepare;
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
    @PostMapping("/{id}/prepare")
    public EvolutionPlanResponse prepare(@PathVariable String id) {
        return EvolutionPlanResponse.from(prepare.prepare(new EvolutionPlanId(id)));
    }
}
