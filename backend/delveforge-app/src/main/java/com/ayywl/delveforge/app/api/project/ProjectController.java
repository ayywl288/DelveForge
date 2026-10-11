package com.ayywl.delveforge.app.api.project;

import com.ayywl.delveforge.application.port.persistence.ProjectHistoryIndex;
import com.ayywl.delveforge.application.project.ProjectFlowContext;
import com.ayywl.delveforge.application.project.ProjectWorkspace;
import com.ayywl.delveforge.application.project.ProjectWorkspaceService;
import com.ayywl.delveforge.application.project.ProjectVisibility;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.user.UserProfile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 技术接口仅转换投影，不取得任何 AI 或执行能力。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectWorkspaceService service;

    public ProjectController(ProjectWorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectSummary> list(@RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "false") boolean deleted) {
        return service.list(offset, limit, deleted).stream().map(ProjectController::summary).toList();
    }

    @DeleteMapping("/{key}")
    public ProjectVisibility delete(@PathVariable String key, @RequestBody VersionRequest request) {
        return service.changeVisibility(key, request.expectedVersion(), true);
    }

    @PostMapping("/{key}/restore")
    public ProjectVisibility restore(@PathVariable String key, @RequestBody VersionRequest request) {
        return service.changeVisibility(key, request.expectedVersion(), false);
    }

    @GetMapping("/{key}")
    public ProjectWorkspaceResponse get(@PathVariable String key) {
        return ProjectWorkspaceResponse.from(service.get(key));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectFlowContext create(@RequestBody CreateRequest request) {
        return service.create(request.name(), request.confirmedUserProfileId());
    }

    @PutMapping("/{id}/context")
    public ProjectFlowContext update(@PathVariable String id, @RequestBody UpdateRequest request) {
        if (request.expectedVersion() == null || request.userProfileId() == null && !request.directionIds().isEmpty()) {
            throw new IllegalArgumentException("Explicit context version and profile basis are required");
        }
        return service.update(id, request.expectedVersion(), request.userProfileId(), request.assetIds(),
                request.repositoryProfileIds(), request.directionIds(), request.planId());
    }

    @PostMapping("/legacy-plans/{id}/resume")
    public ProjectFlowContext resume(@PathVariable String id) {
        return service.adopt(id);
    }

    @GetMapping("/{id}/rounds")
    public List<ProjectSummary> rounds(@PathVariable String id) {
        return service.rounds(id).stream().map(ProjectController::summary).toList();
    }

    @PostMapping("/{id}/planning-rounds")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectFlowContext beginRound(@PathVariable String id, @RequestBody VersionRequest request) {
        return service.beginPlanningRound(id, request.expectedVersion());
    }

    public record VersionRequest(
            @JsonDeserialize(using = ProjectContextVersionDeserializer.class) Long expectedVersion) {
        public VersionRequest {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("Project version is required");
            }
        }
    }

    @GetMapping("/resources")
    public List<ResourceSummary> resources(@RequestParam ProjectHistoryIndex.ResourceKind kind,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return service.resources(kind, offset, limit).stream().map(resource -> {
            if (resource instanceof UserProfile user) {
                String summary = user.projectGoals().isEmpty() ? "用户画像" : user.projectGoals().getFirst();
                return new ResourceSummary(user.id().value(), excerpt(summary), user.status().name(), user.revision(),
                        null, null, null);
            }
            if (resource instanceof SoftwareAsset asset) {
                return new ResourceSummary(asset.id().value(), asset.location(), asset.usageAuthorization().name(), null,
                        null, asset.location(), null);
            }
            RepositoryProfile repo = (RepositoryProfile) resource;
            return new ResourceSummary(repo.id().value(), excerpt(repo.purpose()), null, null, repo.assetId().value(),
                    null, repo.analyzedRevision());
        }).toList();
    }

    private static ProjectSummary summary(ProjectWorkspace workspace) {
        List<Instant> activities = new ArrayList<>();
        if (workspace.context() != null) {
            activities.add(workspace.context().updatedAt());
        }
        Map<String, Long> counts = Map.of();
        if (workspace.plan() != null) {
            counts = workspace.plan().steps().stream().collect(Collectors.groupingBy(step -> step.status().name(),
                    Collectors.counting()));
            workspace.plan().steps().forEach(step -> {
                if (step.executionResult() != null) {
                    activities.add(step.executionResult().executedAt());
                }
                if (step.verificationResult() != null) {
                    activities.add(step.verificationResult().verifiedAt());
                }
            });
        }
        String direction = workspace.directions().stream()
                .filter(item -> workspace.plan() == null ? item.status() == ProductDirectionStatus.SELECTED
                        : item.id().equals(workspace.plan().productDirectionId()))
                .map(item -> item.title()).findFirst().orElse(null);
        String name = workspace.context() != null ? workspace.context().name()
                : direction == null ? "历史演化计划" : excerpt(direction);
        return new ProjectSummary(workspace.key(), name, workspace.legacy(), workspace.stage(), direction,
                workspace.assets().stream().map(asset -> new RepositoryLabel(asset.id().value(), asset.location())).toList(),
                activities.stream().max(Instant::compareTo).orElse(null),
                workspace.plan() == null ? 0 : workspace.plan().steps().size(),
                counts.getOrDefault(EvolutionStepStatus.SUCCEEDED.name(), 0L), counts,
                workspace.plan() == null ? null : workspace.plan().id().value(),
                workspace.plan() == null ? null : workspace.plan().status().name(),
                workspace.workingCopy() == null ? null : workspace.workingCopy().id().value(),
                workspace.workingCopy() == null ? null : workspace.workingCopy().currentRevision(),
                workspace.workingCopy() == null ? null : workspace.workingCopy().lastVerifiedRevision(), workspace.issues(),
                workspace.visibility().version(), workspace.visibility().deletedAt());
    }

    private static String excerpt(String value) {
        return value.substring(0, Math.min(120, value.length()));
    }

    public record CreateRequest(String name, String confirmedUserProfileId) { }

    public record UpdateRequest(
            @JsonDeserialize(using = ProjectContextVersionDeserializer.class) Long expectedVersion,
            @JsonProperty(required = true) String userProfileId,
            List<String> assetIds, List<String> repositoryProfileIds, List<String> directionIds,
            @JsonProperty(required = true) String planId) {
        public UpdateRequest {
            if (assetIds == null || repositoryProfileIds == null || directionIds == null) {
                throw new IllegalArgumentException("Explicit resource lists are required");
            }
        }
    }

    public record ResourceSummary(String id, String summary, String status, Integer revision, String assetId,
            String location, String analyzedRevision) { }

    public record RepositoryLabel(String assetId, String location) { }

    public record ProjectSummary(String key, String name, boolean legacy, String stage, String direction,
            List<RepositoryLabel> repositories, Instant lastActivity, int totalSteps, long succeededSteps,
            Map<String, Long> stepCounts, String planId, String planStatus, String workingCopyId, String currentRevision,
            String lastVerifiedRevision, List<String> issues, long version, Instant deletedAt) { }
}
