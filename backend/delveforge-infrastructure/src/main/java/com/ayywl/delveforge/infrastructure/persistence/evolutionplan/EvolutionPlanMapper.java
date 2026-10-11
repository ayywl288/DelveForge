package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanTransition;
import com.ayywl.delveforge.application.project.ProjectScopeBasis;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 写入时再次核对可变的 Direction 选择状态，防止 AI 调用期间切换方向后提交旧规划。
 */
public interface EvolutionPlanMapper extends BaseMapper<EvolutionPlanDO> {
    /** SQLite 写锁与授权条件核对共用 Plan 插入事务；不更改授权内容。 */
    @Update("""
            UPDATE software_asset SET authorization_revision = authorization_revision
            WHERE id = #{asset.id.value} AND authorization_revision = #{asset.authorizationRevision}
                AND type = #{asset.type} AND source = #{asset.source} AND location = #{asset.location}
                AND read_permission = 1 AND usage_authorization = 'ALLOWED' AND license_info = #{license}
            """)
    int lockPlanningAuthorization(@Param("asset") SoftwareAsset asset, @Param("license") String license);

    @Insert("""
        INSERT INTO evolution_plan
          (id, product_direction_id, base_asset_id, base_repository_profile_id,
           working_copy_id, current_summary, target_problem, target_product, target_differentiation, status, flow_id)
        SELECT #{id}, #{productDirectionId}, #{baseAssetId}, #{baseRepositoryProfileId},
               #{workingCopyId}, #{currentSummary}, #{targetProblem}, #{targetProduct}, #{targetDifferentiation}, #{status},
               (SELECT flow_id FROM product_direction WHERE id = #{productDirectionId})
        WHERE EXISTS (SELECT 1 FROM product_direction
                      WHERE id = #{productDirectionId} AND status = 'SELECTED' AND flow_id IS NOT NULL)
        """)
    int insertForSelectedDirection(EvolutionPlanDO plan);

    /**
     * 对已验证领域候选的读取依据执行条件更新，拒绝并发导致的依据变化。
     */
    @Update("""
        UPDATE evolution_plan SET status = #{plan.status}, working_copy_id = #{copy.id.value}
        WHERE id = #{plan.id.value} AND status = 'PROPOSED' AND working_copy_id IS NULL
          AND product_direction_id = #{plan.productDirectionId.value}
          AND base_asset_id = #{plan.baseAssetId.value}
          AND base_repository_profile_id = #{plan.baseRepositoryProfileId.value}
          AND EXISTS (SELECT 1 FROM product_direction
                      WHERE id = #{plan.productDirectionId.value} AND status = 'SELECTED'
                        AND flow_id = evolution_plan.flow_id)
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
          AND flow_id IS NOT NULL AND flow_id = (SELECT flow_id FROM product_direction
              WHERE id = #{transition.plan.productDirectionId.value})
        """)
    int transitionAtBasis(@Param("transition") EvolutionPlanTransition transition);

    @Select("""
        SELECT COUNT(*) FROM evolution_plan
        WHERE product_direction_id = #{directionId} AND status IN ('PROPOSED', 'ACTIVE')
        """)
    long countContinuingPlans(String directionId);
    @Update("""
            UPDATE project_flow_context SET plan_id = #{plan.id.value}, version = version + 1,
                updated_at = #{updatedAt}
            WHERE id = #{project.contextId} AND version = #{project.expectedVersion} AND plan_id IS NULL
                AND deleted_at IS NULL
                AND flow_id = (SELECT flow_id FROM product_direction WHERE id = #{plan.productDirectionId.value})
                AND user_profile_id = (SELECT user_profile_id FROM product_direction WHERE id = #{plan.productDirectionId.value})
                AND EXISTS (SELECT 1 FROM json_each(repository_profile_ids) WHERE value = #{plan.baseRepositoryProfileId.value})
                AND EXISTS (SELECT 1 FROM json_each(asset_ids) WHERE value = #{plan.baseAssetId.value})
            """)
    int bindProjectPlan(@Param("plan") EvolutionPlan plan,
            @Param("project") ProjectScopeBasis project,
            @Param("updatedAt") String updatedAt);

    @Select("""
            SELECT COUNT(*) FROM evolution_plan p JOIN product_direction d ON d.id = p.product_direction_id
            WHERE p.id = #{id} AND p.flow_id IS NOT NULL AND p.flow_id = d.flow_id
            """)
    int coherentFlow(String id);
}
