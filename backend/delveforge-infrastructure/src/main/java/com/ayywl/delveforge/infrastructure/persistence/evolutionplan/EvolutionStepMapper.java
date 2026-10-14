package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface EvolutionStepMapper extends BaseMapper<EvolutionStepDO> {
    @Update("""
        UPDATE evolution_step SET status = #{next.status}, baseline_revision = #{next.baselineRevision},
            execution_result = #{execution}, verification_result = #{verification}, repair_authorizations = #{authorizations},
            rollback_authorization = #{rollbackAuthorization}, rollback_result = #{rollbackResult},
            execution_failure_result = #{failure}, automatic_rollback_result = #{automaticResult}
        WHERE id = #{stepId.value} AND plan_id = #{plan.id.value} AND status = #{old.status}
          AND baseline_revision IS #{old.baselineRevision}
          AND execution_result IS #{oldExecution} AND verification_result IS #{oldVerification}
          AND repair_authorizations = #{oldAuthorizations} AND goal = #{old.goal} AND scope = #{old.scope}
          AND rollback_authorization IS #{oldRollbackAuthorization} AND rollback_result IS #{oldRollbackResult}
          AND execution_failure_result IS #{oldFailure} AND automatic_rollback_result IS #{oldAutomaticResult}
          AND EXISTS (SELECT 1 FROM evolution_plan p JOIN product_direction d ON d.id = p.product_direction_id
                      WHERE p.id = #{plan.id.value} AND p.status = 'ACTIVE' AND d.status = 'SELECTED'
                        AND p.flow_id IS NOT NULL AND p.flow_id = d.flow_id
                        AND p.working_copy_id = #{copy.id.value} AND p.base_asset_id = #{asset.id.value})
          AND EXISTS (SELECT 1 FROM working_copy WHERE id = #{copy.id.value}
                      AND source_asset_id = #{asset.id.value} AND location = #{copy.location}
                      AND status = #{copy.status} AND current_revision = #{copy.currentRevision}
                      AND last_verified_revision = #{copy.lastVerifiedRevision}
                      AND source_revision = #{copy.sourceRevision})
          AND EXISTS (SELECT 1 FROM software_asset WHERE id = #{asset.id.value}
                      AND type = #{asset.type} AND location = #{asset.location}
                      AND read_permission = #{asset.readPermissionAllowed}
                      AND usage_authorization = #{asset.usageAuthorization} AND license_info = #{license})
          AND NOT EXISTS (SELECT 1 FROM evolution_step s WHERE s.plan_id = #{plan.id.value}
                          AND s.position < evolution_step.position AND s.status <> 'SUCCEEDED')
          AND NOT EXISTS (SELECT 1 FROM evolution_step s WHERE s.plan_id = #{plan.id.value} AND s.id <> #{stepId.value}
                          AND s.status IN ('EXECUTING','VERIFYING','VERIFICATION_FAILED','ROLLING_BACK','RECOVERY_REQUIRED'))
        """)
    int advanceCheckpoint(@Param("plan") EvolutionPlan plan, @Param("stepId") EvolutionStepId id,
            @Param("copy") WorkingCopy copy, @Param("asset") SoftwareAsset asset, @Param("license") String license,
            @Param("old") EvolutionStep old, @Param("next") EvolutionStep next,
            @Param("oldExecution") String oldExecution, @Param("oldVerification") String oldVerification,
            @Param("execution") String execution, @Param("verification") String verification,
            @Param("oldAuthorizations") String oldAuthorizations, @Param("authorizations") String authorizations,
            @Param("oldRollbackAuthorization") String oldRollbackAuthorization,
            @Param("oldRollbackResult") String oldRollbackResult,
            @Param("rollbackAuthorization") String rollbackAuthorization, @Param("rollbackResult") String rollbackResult,
            @Param("oldFailure") String oldFailure, @Param("failure") String failure,
            @Param("oldAutomaticResult") String oldAutomaticResult, @Param("automaticResult") String automaticResult);

    // 仅追加已授权执行的失败诊断，不授予 Workspace 权限；撤权或 Plan 失效不能抹去已发生事实。
    @Update("""
        UPDATE evolution_step SET status = 'ROLLING_BACK', execution_failure_result = #{failure}
        WHERE id = #{stepId.value} AND plan_id = #{plan.id.value} AND status = 'EXECUTING'
          AND baseline_revision = #{old.baselineRevision} AND execution_result IS #{execution}
          AND verification_result IS #{verification} AND repair_authorizations = #{repairs}
          AND rollback_authorization IS #{authorization} AND rollback_result IS #{rollbackResult}
          AND execution_failure_result IS NULL AND automatic_rollback_result IS NULL
          AND goal = #{old.goal} AND scope = #{old.scope}
          AND EXISTS (SELECT 1 FROM evolution_plan p WHERE p.id = #{plan.id.value}
                      AND p.working_copy_id = #{copy.id.value} AND p.base_asset_id = #{copy.sourceAssetId.value})
          AND EXISTS (SELECT 1 FROM working_copy WHERE id = #{copy.id.value}
                      AND source_asset_id = #{copy.sourceAssetId.value} AND location = #{copy.location}
                      AND status = #{copy.status} AND current_revision = #{copy.currentRevision}
                      AND last_verified_revision = #{copy.lastVerifiedRevision}
                      AND source_revision = #{copy.sourceRevision})
        """)
    int diagnoseFailure(@Param("plan") EvolutionPlan plan, @Param("stepId") EvolutionStepId id,
            @Param("copy") WorkingCopy copy, @Param("old") EvolutionStep old,
            @Param("execution") String execution, @Param("verification") String verification,
            @Param("repairs") String repairs, @Param("authorization") String authorization,
            @Param("rollbackResult") String rollbackResult, @Param("failure") String failure);

    // 只记录已授权回滚的安全阻塞事实；权限撤销不应妨碍诊断落盘，此操作没有 Workspace 写能力。
    @Update("""
        UPDATE evolution_step SET status = 'RECOVERY_REQUIRED', rollback_result = #{result},
            automatic_rollback_result = #{automaticResult}
        WHERE id = #{stepId.value} AND plan_id = #{plan.id.value} AND status = 'ROLLING_BACK'
          AND baseline_revision = #{old.baselineRevision} AND execution_result IS #{execution}
          AND verification_result IS #{verification} AND repair_authorizations = #{repairs}
          AND rollback_authorization IS #{authorization} AND rollback_result IS NULL
          AND execution_failure_result IS #{failure} AND automatic_rollback_result IS #{oldAutomaticResult}
          AND goal = #{old.goal} AND scope = #{old.scope}
          AND EXISTS (SELECT 1 FROM evolution_plan p WHERE p.id = #{plan.id.value}
                      AND p.working_copy_id = #{copy.id.value} AND p.base_asset_id = #{copy.sourceAssetId.value})
          AND EXISTS (SELECT 1 FROM working_copy WHERE id = #{copy.id.value}
                      AND source_asset_id = #{copy.sourceAssetId.value} AND location = #{copy.location}
                      AND status = #{copy.status} AND current_revision = #{copy.currentRevision}
                      AND last_verified_revision = #{copy.lastVerifiedRevision}
                      AND source_revision = #{copy.sourceRevision})
        """)
    int blockRollback(@Param("plan") EvolutionPlan plan, @Param("stepId") EvolutionStepId id,
            @Param("copy") WorkingCopy copy, @Param("old") EvolutionStep old,
            @Param("execution") String execution, @Param("verification") String verification,
            @Param("repairs") String repairs, @Param("authorization") String authorization, @Param("result") String result,
            @Param("failure") String failure, @Param("oldAutomaticResult") String oldAutomaticResult,
            @Param("automaticResult") String automaticResult);

    @Update("""
        UPDATE evolution_step SET status = 'READY'
        WHERE id = #{stepId.value} AND plan_id = #{plan.id.value}
          AND status = 'PENDING_CONFIRMATION' AND baseline_revision IS NULL
          AND EXISTS (SELECT 1 FROM evolution_plan p JOIN product_direction d ON d.id = p.product_direction_id
                      WHERE p.id = #{plan.id.value} AND p.status = 'ACTIVE' AND d.status = 'SELECTED'
                        AND p.flow_id IS NOT NULL AND p.flow_id = d.flow_id
                        AND p.working_copy_id = #{copy.id.value} AND p.base_asset_id = #{asset.id.value})
          AND EXISTS (SELECT 1 FROM working_copy WHERE id = #{copy.id.value}
                      AND source_asset_id = #{asset.id.value} AND location = #{copy.location}
                      AND status = #{copy.status} AND current_revision = #{copy.currentRevision}
                      AND last_verified_revision = #{copy.lastVerifiedRevision}
                      AND source_revision = #{copy.sourceRevision})
          AND EXISTS (SELECT 1 FROM software_asset WHERE id = #{asset.id.value}
                      AND type = #{asset.type} AND location = #{asset.location}
                      AND read_permission = #{asset.readPermissionAllowed}
                      AND usage_authorization = #{asset.usageAuthorization} AND license_info = #{license})
          AND NOT EXISTS (SELECT 1 FROM evolution_step s WHERE s.plan_id = #{plan.id.value}
                          AND s.position < evolution_step.position AND s.status <> 'SUCCEEDED')
          AND NOT EXISTS (SELECT 1 FROM evolution_step s WHERE s.plan_id = #{plan.id.value}
                          AND s.status IN ('EXECUTING','VERIFYING','VERIFICATION_FAILED','ROLLING_BACK','RECOVERY_REQUIRED'))
        """)
    int confirmAtBasis(@Param("plan") EvolutionPlan plan, @Param("stepId") EvolutionStepId stepId,
            @Param("copy") WorkingCopy copy, @Param("asset") SoftwareAsset asset, @Param("license") String license);
}
