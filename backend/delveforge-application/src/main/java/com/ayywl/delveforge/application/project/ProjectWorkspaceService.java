package com.ayywl.delveforge.application.project;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProjectContextRepository;
import com.ayywl.delveforge.application.port.persistence.ProjectHistoryIndex;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyStorageInspection;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 项目导航与历史读取；不持有 AI、Mutation、Verification 或生命周期提交能力。 */
public final class ProjectWorkspaceService {
    private final ProjectContextRepository contexts;
    private final ProjectHistoryIndex index;
    private final UserProfileRepository users;
    private final SoftwareAssetRepository assets;
    private final RepositoryProfileRepository repositories;
    private final ProductDirectionRepository directions;
    private final EvolutionPlanRepository plans;
    private final WorkingCopyRepository copies;
    private final WorkingCopyStorageInspection storage;
    private final Clock clock;

    public ProjectWorkspaceService(ProjectContextRepository contexts, ProjectHistoryIndex index,
            UserProfileRepository users, SoftwareAssetRepository assets, RepositoryProfileRepository repositories,
            ProductDirectionRepository directions, EvolutionPlanRepository plans, WorkingCopyRepository copies,
            WorkingCopyStorageInspection storage, Clock clock) {
        this.contexts = contexts;
        this.index = index;
        this.users = users;
        this.assets = assets;
        this.repositories = repositories;
        this.directions = directions;
        this.plans = plans;
        this.copies = copies;
        this.storage = storage;
        this.clock = clock;
    }

    public List<ProjectWorkspace> list(int offset, int limit) {
        return list(offset, limit, false);
    }

    public List<ProjectWorkspace> list(int offset, int limit, boolean deleted) {
        requirePage(offset, limit);
        return index.historyKeys(offset, limit, deleted).stream().map(this::get).toList();
    }

    /** 精确工作台身份和版本；只改导航标记，不读写 Git 或执行生命周期。 */
    public ProjectVisibility changeVisibility(String key, long expectedVersion, boolean deleted) {
        if (key == null || !(key.startsWith("project:") || key.startsWith("plan:")) || expectedVersion < 0) {
            throw new IllegalArgumentException("Project identity and version are required");
        }
        ProjectFlowContext.requireId(key.substring(key.startsWith("project:") ? 8 : 5));
        if (deleted) {
            ProjectWorkspace workspace = get(key);
            if (workspace.issues().stream().anyMatch(issue -> issue.equals("PLAN_MISSING")
                    || issue.equals("PLAN_UNREADABLE") || issue.equals("WORKING_COPY_MISSING")
                    || issue.equals("WORKING_COPY_UNREADABLE"))) {
                throw new ProjectDeletionBlockedException();
            }
        }
        return contexts.changeVisibility(key, expectedVersion, deleted, clock.instant());
    }

    public List<?> resources(ProjectHistoryIndex.ResourceKind kind, int offset, int limit) {
        requirePage(offset, limit);
        return index.resourceIds(kind, offset, limit).stream().map(id -> switch (kind) {
            case USER_PROFILE -> users.findById(new UserProfileId(id)).orElseThrow(ProjectNotFoundException::new);
            case SOFTWARE_ASSET -> assets.findById(new SoftwareAssetId(id)).orElseThrow(ProjectNotFoundException::new);
            case REPOSITORY_PROFILE -> repositories.findById(new RepositoryProfileId(id))
                    .orElseThrow(ProjectNotFoundException::new);
        }).toList();
    }

    public ProjectFlowContext create(String name, String confirmedProfileId) {
        Integer revision = null;
        if (confirmedProfileId != null) {
            UserProfile user = users.findById(new UserProfileId(confirmedProfileId))
                    .orElseThrow(ProjectNotFoundException::new);
            if (user.status() != UserProfileStatus.CONFIRMED) {
                throw new ProjectContextConflictException();
            }
            revision = user.revision();
        }
        Instant now = clock.instant();
        ProjectFlowContext context = new ProjectFlowContext(UUID.randomUUID().toString(), name, confirmedProfileId,
                List.of(), List.of(), List.of(), null, now, now, 0);
        contexts.create(context, revision);
        return context;
    }

    /** 保存显式选取的身份，保留已有关联；Plan 一旦绑定，另一个 Plan 必须使用另一个上下文。 */
    public ProjectFlowContext update(String id, long expectedVersion, String profileId, List<String> assetIds,
            List<String> repositoryIds, List<String> directionIds, String planId) {
        ProjectFlowContext current = contexts.findById(id).orElseThrow(ProjectNotFoundException::new);
        if (current.deletedAt() != null || current.version() != expectedVersion
                || current.userProfileId() != null && !current.userProfileId().equals(profileId)
                || current.planId() != null && !current.planId().equals(planId)
                || !assetIds.containsAll(current.assetIds())
                || !repositoryIds.containsAll(current.repositoryProfileIds())
                || !directionIds.containsAll(current.directionIds())) {
            throw new ProjectContextConflictException();
        }
        ProjectFlowContext candidate = new ProjectFlowContext(id, current.name(), profileId, assetIds, repositoryIds,
                directionIds, planId, current.createdAt(), clock.instant(), Math.addExact(expectedVersion, 1),
                current.flowId(), current.previousContextId());
        ProjectWorkspace workspace = load("project:" + id, candidate, false);
        if (workspace.issues().stream().anyMatch(ProjectWorkspaceService::invalidAssociation)) {
            throw new ProjectContextConflictException();
        }
        contexts.update(candidate, expectedVersion);
        return candidate;
    }

    public ProjectWorkspace get(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Project key is required");
        }
        if (key.startsWith("project:")) {
            String id = key.substring(8);
            ProjectFlowContext.requireId(id);
            return load(key, contexts.findById(id).orElseThrow(ProjectNotFoundException::new), false);
        }
        if (key.startsWith("plan:")) {
            String id = key.substring(5);
            ProjectFlowContext.requireId(id);
            List<String> issues = new ArrayList<>();
            EvolutionPlan plan = read(() -> plans.findById(new EvolutionPlanId(id)), "PLAN", issues);
            if (plan == null) {
                if (issues.contains("PLAN_MISSING")) {
                    throw new ProjectNotFoundException();
                }
                ProjectVisibility visibility = contexts.visibility(key);
                if (visibility.deletedAt() != null) {
                    issues.add("PROJECT_DELETED");
                }
                return new ProjectWorkspace(key, null, true, null, List.of(), List.of(), List.of(), null, null,
                        "PLANNING", null, List.copyOf(issues), visibility);
            }
            // 旧记录仅沿保存的引用读取；EPOCH 只作内部临时值，响应不发布伪造创建时间。
            return load(key, legacyBasis(plan), true);
        }
        throw new IllegalArgumentException("Unknown project key kind");
    }

    /** 用户明确继续旧 Plan 后才建立记录；重复认领不创建第二个项目或修改业务状态。 */
    public ProjectFlowContext adopt(String planId) {
        ProjectWorkspace workspace = get("plan:" + planId);
        if (workspace.issues().stream().anyMatch(ProjectWorkspaceService::invalidAssociation)) {
            throw new ProjectContextConflictException();
        }
        String flow = directions.flowId(workspace.plan().productDirectionId());
        if (flow == null) {
            throw new EvolutionFlowScopeException();
        }
        ProjectFlowContext basis = legacyBasis(workspace.plan());
        Instant now = clock.instant();
        return contexts.adoptPlan(new ProjectFlowContext(UUID.randomUUID().toString(), basis.name(),
                basis.userProfileId(), basis.assetIds(), basis.repositoryProfileIds(), basis.directionIds(), planId,
                now, now, 0, directions.flowId(workspace.plan().productDirectionId()), null));
    }

    public List<ProjectWorkspace> rounds(String contextId) {
        ProjectFlowContext basis = contexts.findById(contextId).orElseThrow(ProjectNotFoundException::new);
        return contexts.findByFlow(basis.flowId()).stream()
                .map(context -> load("project:" + context.id(), context, false)).toList();
    }

    /** 用户主动开始新轮次，原 planId 不变；不读取旧副本源码、不调用 AI 或执行。 */
    public ProjectFlowContext beginPlanningRound(String contextId, long expectedVersion) {
        ProjectFlowContext basis = contexts.findById(contextId).orElseThrow(ProjectNotFoundException::new);
        if (basis.deletedAt() != null || basis.version() != expectedVersion || basis.planId() == null) {
            throw new ProjectContextConflictException();
        }
        ProjectWorkspace workspace = load("project:" + contextId, basis, false);
        if (workspace.issues().stream().anyMatch(ProjectWorkspaceService::invalidAssociation)
                || workspace.issues().contains("FLOW_OWNERSHIP_UNKNOWN")
                || workspace.plan().status() != com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus.SUPERSEDED) {
            throw new EvolutionFlowScopeException();
        }
        Optional<ProjectFlowContext> existing = contexts.findByFlow(basis.flowId()).stream()
                .filter(context -> contextId.equals(context.previousContextId())).findFirst();
        if (existing.isPresent()) {
            if (existing.get().deletedAt() != null) {
                throw new ProjectContextConflictException();
            }
            return existing.get();
        }
        Instant now = clock.instant();
        ProjectFlowContext candidate = new ProjectFlowContext(UUID.randomUUID().toString(), basis.name(),
                basis.userProfileId(), workspace.assets().stream().map(asset -> asset.id().value()).toList(),
                workspace.repositoryProfiles().stream().map(profile -> profile.id().value()).toList(),
                workspace.directions().stream().map(direction -> direction.id().value()).toList(), null,
                now, now, 0, basis.flowId(), basis.id());
        contexts.createSuccessor(basis, candidate);
        return candidate;
    }

    private ProjectFlowContext legacyBasis(EvolutionPlan plan) {
        List<String> derivationIssues = new ArrayList<>();
        ProductDirection direction = read(() -> directions.findById(plan.productDirectionId()),
                "DIRECTION", derivationIssues);
        LinkedHashSet<String> repoIds = new LinkedHashSet<>();
        LinkedHashSet<String> assetIds = new LinkedHashSet<>();
        repoIds.add(plan.baseRepositoryProfileId().value());
        assetIds.add(plan.baseAssetId().value());
        if (direction != null) {
            direction.repositoryProfileIds().forEach(id -> repoIds.add(id.value()));
            direction.candidateAssetIds().forEach(id -> assetIds.add(id.value()));
        }
        for (String id : repoIds) {
            RepositoryProfile repo = read(() -> repositories.findById(new RepositoryProfileId(id)),
                    "REPOSITORY", derivationIssues);
            if (repo != null) {
                assetIds.add(repo.assetId().value());
            }
        }
        return new ProjectFlowContext("legacy", direction == null ? "历史演化计划" : title(direction.title()),
                direction == null ? null : direction.userProfileId().value(), List.copyOf(assetIds),
                List.copyOf(repoIds), List.of(plan.productDirectionId().value()), plan.id().value(),
                Instant.EPOCH, Instant.EPOCH, 0);
    }

    private ProjectWorkspace load(String key, ProjectFlowContext basis, boolean legacy) {
        List<String> issues = new ArrayList<>();
        ProjectVisibility visibility = legacy ? contexts.visibility(key)
                : new ProjectVisibility(key, basis.version(), basis.deletedAt());
        if (visibility.deletedAt() != null) {
            issues.add("PROJECT_DELETED");
        }
        LinkedHashSet<String> assetIds = new LinkedHashSet<>(basis.assetIds());
        LinkedHashSet<String> repositoryIds = new LinkedHashSet<>(basis.repositoryProfileIds());
        if (!legacy) {
            // 后续轮次新增的分析仍按明确的同 flowId 上下文引用读取，不按路径拼装。
            contexts.findByFlow(basis.flowId()).forEach(context -> {
                assetIds.addAll(context.assetIds());
                repositoryIds.addAll(context.repositoryProfileIds());
            });
        }
        UserProfile user = basis.userProfileId() == null ? null
                : read(() -> users.findById(new UserProfileId(basis.userProfileId())), "USER_PROFILE", issues);
        List<SoftwareAsset> loadedAssets = new ArrayList<>();
        for (String id : assetIds) {
            SoftwareAsset asset = read(() -> assets.findById(new SoftwareAssetId(id)), "ASSET", issues);
            if (asset != null) {
                loadedAssets.add(asset);
            }
        }
        List<RepositoryProfile> loadedRepos = new ArrayList<>();
        for (String id : repositoryIds) {
            RepositoryProfile repo = read(() -> repositories.findById(new RepositoryProfileId(id)), "REPOSITORY", issues);
            if (repo != null) {
                loadedRepos.add(repo);
                if (!assetIds.contains(repo.assetId().value())) {
                    issues.add("ASSOCIATION_MISMATCH");
                }
            }
        }
        List<ProductDirection> loadedDirections = new ArrayList<>();
        LinkedHashSet<String> flowDirections = new LinkedHashSet<>(basis.directionIds());
        if (!legacy) {
            directions.findByFlow(basis.flowId()).forEach(d -> flowDirections.add(d.id().value()));
        }
        for (String id : flowDirections) {
            ProductDirection direction = read(() -> directions.findById(new ProductDirectionId(id)), "DIRECTION", issues);
            if (direction != null) {
                loadedDirections.add(direction);
                String owner = directions.flowId(direction.id());
                if (owner == null) {
                    issues.add("FLOW_OWNERSHIP_UNKNOWN");
                } else if (!legacy && !owner.equals(basis.flowId())) {
                    issues.add("ASSOCIATION_MISMATCH");
                }
                if (!direction.userProfileId().value().equals(basis.userProfileId())
                        || direction.repositoryProfileIds().stream().anyMatch(ref ->
                                !repositoryIds.contains(ref.value()))
                        || direction.candidateAssetIds().stream().anyMatch(ref -> !assetIds.contains(ref.value()))) {
                    issues.add("ASSOCIATION_MISMATCH");
                }
            }
        }
        EvolutionPlan plan = basis.planId() == null ? null
                : read(() -> plans.findById(new EvolutionPlanId(basis.planId())), "PLAN", issues);
        WorkingCopy copy = null;
        Integer boundRevision = null;
        if (plan != null) {
            if (!flowDirections.contains(plan.productDirectionId().value())
                    || !assetIds.contains(plan.baseAssetId().value())
                    || loadedRepos.stream().noneMatch(repo -> repo.id().equals(plan.baseRepositoryProfileId())
                            && repo.assetId().equals(plan.baseAssetId()))) {
                issues.add("ASSOCIATION_MISMATCH");
            }
            ProductDirection target = loadedDirections.stream().filter(d -> d.id().equals(plan.productDirectionId()))
                    .findFirst().orElse(null);
            if (target != null) {
                boundRevision = target.userProfileRevision();
                if (user != null && (user.revision() != boundRevision || user.status() != UserProfileStatus.CONFIRMED)) {
                    issues.add("PROFILE_BASIS_CHANGED");
                }
                if (target.status() != ProductDirectionStatus.SELECTED) {
                    issues.add("DIRECTION_NOT_SELECTED");
                }
            }
            for (SoftwareAsset asset : loadedAssets) {
                if (asset.id().equals(plan.baseAssetId()) && (!asset.readPermissionAllowed()
                        || !asset.usageAuthorization().name().equals("ALLOWED") || asset.licenseInfo().isEmpty())) {
                    issues.add("ASSET_AUTHORIZATION_REQUIRED");
                }
            }
            if (plan.workingCopyId() != null) {
                copy = read(() -> copies.findById(new WorkingCopyId(plan.workingCopyId())), "WORKING_COPY", issues);
                if (copy != null) {
                    String sourceRevision = copy.sourceRevision();
                    if (!copy.sourceAssetId().equals(plan.baseAssetId())
                            || loadedRepos.stream().noneMatch(repo -> repo.id().equals(plan.baseRepositoryProfileId())
                                    && repo.analyzedRevision().equals(sourceRevision))) {
                        issues.add("ASSOCIATION_MISMATCH");
                    }
                    String storageIssue = storage.inspect(copy);
                    if (storageIssue != null) {
                        issues.add(storageIssue);
                    }
                }
            }
            if (plan.steps().stream().anyMatch(step -> step.status() == EvolutionStepStatus.EXECUTING
                    || step.status() == EvolutionStepStatus.VERIFYING)) {
                issues.add("EXECUTION_IN_PROGRESS_OR_UNRESOLVED");
            }
        }
        String stage = plan != null ? plan.workingCopyId() == null ? "PLANNING" : "EVOLUTION"
                : !loadedDirections.isEmpty() ? "DIRECTION"
                : user == null || user.status() != UserProfileStatus.CONFIRMED ? "DISCOVERY"
                : loadedRepos.isEmpty() ? "REPOSITORY" : "DIRECTION";
        return new ProjectWorkspace(key, legacy ? null : basis, legacy, user, List.copyOf(loadedAssets),
                List.copyOf(loadedRepos), List.copyOf(loadedDirections), plan, copy, stage, boundRevision,
                issues.stream().distinct().toList(), visibility);
    }

    private static <T> T read(Supplier<Optional<T>> read, String kind, List<String> issues) {
        try {
            Optional<T> value = read.get();
            if (value.isEmpty()) {
                issues.add(kind + "_MISSING");
            }
            return value.orElse(null);
        } catch (RuntimeException failure) {
            // 历史投影允许展示可读部分，明确暴露结构化错误；不回显异常消息或虚构关联。
            issues.add(kind + "_UNREADABLE");
            return null;
        }
    }

    private static boolean invalidAssociation(String issue) {
        return !issue.startsWith("WORKSPACE_") && (issue.endsWith("_MISSING")
                || issue.endsWith("_UNREADABLE") || issue.equals("ASSOCIATION_MISMATCH") || issue.equals("PROJECT_DELETED"));
    }

    private static String title(String value) {
        return value.substring(0, Math.min(120, value.length()));
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid history page");
        }
    }
}
