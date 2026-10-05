package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanTransition;

/** The write itself checks the mutable selection basis, closing the AI-call race. */
public interface EvolutionPlanMapper extends BaseMapper<EvolutionPlanDO> {
    @Insert("""
        INSERT INTO evolution_plan
          (id, product_direction_id, base_asset_id, base_repository_profile_id,
           working_copy_id, current_summary, target_problem, target_product, target_differentiation, status)
        SELECT #{id}, #{productDirectionId}, #{baseAssetId}, #{baseRepositoryProfileId},
               #{workingCopyId}, #{currentSummary}, #{targetProblem}, #{targetProduct}, #{targetDifferentiation}, #{status}
        WHERE EXISTS (SELECT 1 FROM product_direction
                      WHERE id = #{productDirectionId} AND status = 'SELECTED')
        """)
    int insertForSelectedDirection(EvolutionPlanDO plan);

    /** Storage compare-and-set guards on the validated domain candidate's read basis. */
    @Update("""
        UPDATE evolution_plan SET status = #{plan.status}, working_copy_id = #{copy.id.value}
        WHERE id = #{plan.id.value} AND status = 'PROPOSED' AND working_copy_id IS NULL
          AND product_direction_id = #{plan.productDirectionId.value}
          AND base_asset_id = #{plan.baseAssetId.value}
          AND base_repository_profile_id = #{plan.baseRepositoryProfileId.value}
          AND EXISTS (SELECT 1 FROM product_direction
                      WHERE id = #{plan.productDirectionId.value} AND status = 'SELECTED')
          AND EXISTS (SELECT 1 FROM repository_profile
                      WHERE id = #{plan.baseRepositoryProfileId.value}
                        AND asset_id = #{copy.sourceAssetId.value} AND analyzed_revision = #{copy.sourceRevision})
          AND EXISTS (SELECT 1 FROM software_asset
                      WHERE id = #{asset.id.value} AND type = #{asset.type} AND source = #{asset.source}
                        AND location = #{asset.location} AND read_permission = #{asset.readPermissionAllowed}
                        AND license_info = #{license} AND usage_authorization = #{asset.usageAuthorization})
        """)
    int activateAtBasis(@Param("plan") EvolutionPlan plan, @Param("copy") WorkingCopy copy,
            @Param("asset") SoftwareAsset asset, @Param("license") String license);

    @Update("""
        UPDATE evolution_plan SET status = #{transition.plan.status}
        WHERE id = #{transition.plan.id.value} AND status = #{transition.expectedStatus}
          AND working_copy_id IS #{transition.expectedWorkingCopyId}
          AND product_direction_id = #{transition.plan.productDirectionId.value}
        """)
    int transitionAtBasis(@Param("transition") EvolutionPlanTransition transition);

    @Select("""
        SELECT COUNT(*) FROM evolution_plan
        WHERE product_direction_id = #{directionId} AND status IN ('PROPOSED', 'ACTIVE')
        """)
    long countContinuingPlans(String directionId);
}
