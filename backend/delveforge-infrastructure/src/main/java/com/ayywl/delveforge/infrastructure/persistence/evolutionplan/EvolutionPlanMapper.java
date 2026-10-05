package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

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
}
