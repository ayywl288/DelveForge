package com.ayywl.delveforge.app.api.project;

import com.ayywl.delveforge.app.api.evidence.EvidencePayload;
import com.ayywl.delveforge.app.api.evolutionplan.EvolutionPlanResponse;
import com.ayywl.delveforge.app.api.evolutionplan.WorkingCopyController.WorkingCopyResponse;
import com.ayywl.delveforge.app.api.productdirection.ProductDirectionResponse;
import com.ayywl.delveforge.app.api.repositoryprofile.RepositoryProfileResponse;
import com.ayywl.delveforge.app.api.softwareasset.SoftwareAssetResponse;
import com.ayywl.delveforge.app.api.userprofile.UserProfileResponse;
import com.ayywl.delveforge.application.project.ProjectFlowContext;
import com.ayywl.delveforge.application.project.ProjectWorkspace;
import com.ayywl.delveforge.application.project.ProjectVisibility;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.user.UserProfile;

import java.util.List;

/** 当前画像与 Direction 的历史版本绑定分开返回，不伪造旧聊天或模型响应。 */
public record ProjectWorkspaceResponse(String key, ProjectFlowContext context, boolean legacy,
        UserProfileResponse userProfile, List<SoftwareAssetResponse> assets,
        List<RepositoryProfileResponse> repositoryProfiles, List<ProductDirectionResponse> directions,
        EvolutionPlanResponse plan, WorkingCopyResponse workingCopy, String stage,
        Integer boundUserProfileRevision, List<String> issues, ProjectVisibility visibility) {
    public static ProjectWorkspaceResponse from(ProjectWorkspace workspace) {
        var copy = workspace.workingCopy();
        return new ProjectWorkspaceResponse(workspace.key(), workspace.context(), workspace.legacy(),
                user(workspace.userProfile()), workspace.assets().stream().map(ProjectWorkspaceResponse::asset).toList(),
                workspace.repositoryProfiles().stream().map(RepositoryProfileResponse::from).toList(),
                workspace.directions().stream().map(ProductDirectionResponse::from).toList(),
                workspace.plan() == null ? null : EvolutionPlanResponse.from(workspace.plan()),
                copy == null ? null : new WorkingCopyResponse(copy.id().value(), copy.sourceAssetId().value(),
                        copy.sourceRevision(), copy.location(), copy.currentRevision(), copy.lastVerifiedRevision(),
                        copy.status()), workspace.stage(), workspace.boundUserProfileRevision(), workspace.issues(),
                workspace.visibility());
    }

    private static UserProfileResponse user(UserProfile profile) {
        return profile == null ? null : new UserProfileResponse(profile.id().value(), profile.status(), profile.revision(),
                profile.interests(), profile.behaviors(), profile.painPoints(), profile.technicalCapabilities(),
                profile.projectGoals(), profile.constraints(), profile.evidence().stream().map(EvidencePayload::from).toList());
    }

    private static SoftwareAssetResponse asset(SoftwareAsset asset) {
        return new SoftwareAssetResponse(asset.id().value(), asset.type(), asset.source(), asset.location(),
                asset.readPermissionAllowed(), asset.licenseInfo().orElse(null), asset.usageAuthorization(),
                asset.authorizationRevision());
    }
}
