package com.ayywl.delveforge.application.project;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.user.UserProfile;

import java.util.List;

/** 来自持久化对象的只读投影；issues 明确表示恢复局限，不制造历史对话。 */
public record ProjectWorkspace(String key, ProjectFlowContext context, boolean legacy,
        UserProfile userProfile, List<SoftwareAsset> assets, List<RepositoryProfile> repositoryProfiles,
        List<ProductDirection> directions, EvolutionPlan plan, WorkingCopy workingCopy,
        String stage, Integer boundUserProfileRevision, List<String> issues) {
}
