package com.ayywl.delveforge.infrastructure.persistence.project;

import com.ayywl.delveforge.application.port.persistence.ProjectContextRepository;
import com.ayywl.delveforge.application.port.persistence.ProjectHistoryIndex;
import com.ayywl.delveforge.application.project.ProjectContextConflictException;
import com.ayywl.delveforge.application.project.ProjectFlowContext;
import com.ayywl.delveforge.application.project.ProjectDeletionBlockedException;
import com.ayywl.delveforge.application.project.ProjectNotFoundException;
import com.ayywl.delveforge.application.project.ProjectVisibility;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SqliteProjectContextRepository implements ProjectContextRepository, ProjectHistoryIndex {
    private final ProjectContextMapper mapper;
    private final ObjectMapper json;

    public SqliteProjectContextRepository(ProjectContextMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Optional<ProjectFlowContext> findById(String id) {
        return Optional.ofNullable(mapper.find(id)).map(this::context);
    }

    @Override
    @Transactional
    public void create(ProjectFlowContext context, Integer expectedConfirmedRevision) {
        if (mapper.insert(row(context), expectedConfirmedRevision) != 1) {
            throw new ProjectContextConflictException();
        }
    }

    @Override
    @Transactional
    public void update(ProjectFlowContext candidate, long expectedVersion) {
        if (mapper.update(row(candidate), expectedVersion) != 1) {
            throw new ProjectContextConflictException();
        }
    }

    @Override
    @Transactional
    public ProjectFlowContext adoptPlan(ProjectFlowContext candidate) {
        // INSERT 首先取得写锁；唯一 Plan 引用解决并发认领，不靠进程内缓存。
        mapper.insert(row(candidate), null);
        ProjectContextRow saved = mapper.findPlan(candidate.planId());
        if (saved == null || saved.deletedAt() != null) {
            throw new ProjectContextConflictException();
        }
        return context(saved);
    }

    @Override
    public List<ProjectFlowContext> findByFlow(String flowId) {
        return mapper.findFlow(flowId).stream().map(this::context).toList();
    }

    @Override
    @Transactional
    public void createSuccessor(ProjectFlowContext predecessor, ProjectFlowContext candidate) {
        if (!predecessor.id().equals(candidate.previousContextId())
                || !predecessor.flowId().equals(candidate.flowId()) || candidate.planId() != null
                || mapper.lockSuccessor(predecessor, candidate.updatedAt().toString()) != 1) {
            throw new ProjectContextConflictException();
        }
        create(candidate, null);
    }

    @Override
    public List<String> historyKeys(int offset, int limit) {
        return historyKeys(offset, limit, false);
    }

    @Override
    public List<String> historyKeys(int offset, int limit, boolean deleted) {
        return mapper.history(offset, limit, deleted);
    }

    @Override
    public ProjectVisibility visibility(String key) {
        if (key.startsWith("project:")) {
            ProjectContextRow row = mapper.find(key.substring(8));
            if (row == null) {
                throw new ProjectNotFoundException();
            }
            return new ProjectVisibility(key, row.version(), instant(row.deletedAt()));
        }
        String id = key.substring(5);
        if (mapper.planExists(id) != 1) {
            throw new ProjectNotFoundException();
        }
        LegacyVisibilityRow row = mapper.legacyVisibility(id);
        return new ProjectVisibility(key, row == null ? 0 : row.version(), row == null ? null : instant(row.deletedAt()));
    }

    @Override
    @Transactional
    public ProjectVisibility changeVisibility(String key, long expectedVersion, boolean deleted, Instant now) {
        boolean legacy = key.startsWith("plan:");
        String id = key.substring(legacy ? 5 : 8);
        String planId;
        String flowId;
        // 首先取得 SQLite 写锁，再读取和检查 Step；并发删除、确认和检查点不能穿过检查窗口。
        if (legacy) {
            mapper.ensureLegacyVisibility(id);
            if (mapper.lockLegacyVisibility(id, expectedVersion) != 1) {
                if (mapper.planExists(id) != 1) {
                    throw new ProjectNotFoundException();
                }
                throw new ProjectContextConflictException();
            }
            planId = id;
            flowId = null;
        } else {
            if (mapper.lockVisibility(id, expectedVersion) != 1) {
                if (mapper.find(id) == null) {
                    throw new ProjectNotFoundException();
                }
                throw new ProjectContextConflictException();
            }
            ProjectContextRow row = mapper.find(id);
            planId = row.planId();
            flowId = row.flowId();
        }
        if (deleted && mapper.unsafeExecutionCount(planId, flowId) != 0) {
            throw new ProjectDeletionBlockedException();
        }
        String deletedAt = deleted ? now.toString() : null;
        int changed = legacy ? mapper.legacyVisibilityUpdate(id, expectedVersion, deleted, deletedAt)
                : mapper.visibility(id, expectedVersion, deleted, deletedAt, now.toString());
        if (changed != 1) {
            throw new ProjectContextConflictException();
        }
        return new ProjectVisibility(key, Math.addExact(expectedVersion, 1), deleted ? now : null);
    }

    @Override
    public List<String> resourceIds(ResourceKind kind, int offset, int limit) {
        return mapper.resources(kind.name(), offset, limit);
    }

    private ProjectContextRow row(ProjectFlowContext context) {
        return new ProjectContextRow(context.id(), context.name(), context.userProfileId(), encode(context.assetIds()),
                encode(context.repositoryProfileIds()), encode(context.directionIds()), context.planId(),
                context.createdAt().toString(), context.updatedAt().toString(), context.version(),
                context.flowId(), context.previousContextId(), context.deletedAt() == null ? null : context.deletedAt().toString());
    }

    private ProjectFlowContext context(ProjectContextRow row) {
        return new ProjectFlowContext(row.id(), row.name(), row.userProfileId(), decode(row.assetIds()),
                decode(row.repositoryProfileIds()), decode(row.directionIds()), row.planId(),
                Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()), row.version(),
                row.flowId(), row.previousContextId(), instant(row.deletedAt()));
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private String encode(List<String> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode project identities", failure);
        }
    }

    private List<String> decode(String ids) {
        try {
            return json.readValue(ids, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot read project identities", failure);
        }
    }
}
