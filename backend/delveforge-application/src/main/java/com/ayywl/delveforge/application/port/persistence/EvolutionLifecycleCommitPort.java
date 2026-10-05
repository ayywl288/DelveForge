package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import java.util.List;

/**
 * 原子提交 Domain 已形成的跨 Aggregate 生命周期候选。
 * 实现必须拒绝过期的生命周期或授权依据，并在失败（含提交失败）时回滚全部写入。
 * 既有内容与历史元数据必须保留。
 */
public interface EvolutionLifecycleCommitPort {
    void commitActivation(EvolutionPlan activePlan, WorkingCopy readyCopy, SoftwareAsset authorizationBasis);
    void commitDirectionSelection(List<ProductDirectionTransition> directions, List<EvolutionPlanTransition> plans);
}
