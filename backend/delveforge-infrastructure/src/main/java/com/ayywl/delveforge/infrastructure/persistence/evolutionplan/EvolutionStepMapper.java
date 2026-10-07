package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.*;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface EvolutionStepMapper extends BaseMapper<EvolutionStepDO> {
    @Update("""
        UPDATE evolution_step SET status = 'READY'
        WHERE id = #{stepId.value} AND plan_id = #{plan.id.value}
          AND status = 'PENDING_CONFIRMATION' AND baseline_revision IS NULL
          AND EXISTS (SELECT 1 FROM evolution_plan p JOIN product_direction d ON d.id = p.product_direction_id
                      WHERE p.id = #{plan.id.value} AND p.status = 'ACTIVE' AND d.status = 'SELECTED'
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
