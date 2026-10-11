package com.ayywl.delveforge.infrastructure.persistence.project;

import com.ayywl.delveforge.application.port.persistence.ProjectContextRepository;
import com.ayywl.delveforge.application.port.persistence.ProjectHistoryIndex;
import com.ayywl.delveforge.application.project.ProjectContextConflictException;
import com.ayywl.delveforge.application.project.ProjectFlowContext;

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
        if (saved == null) {
            throw new ProjectContextConflictException();
        }
        return context(saved);
    }

    @Override
    public List<String> historyKeys(int offset, int limit) {
        return mapper.history(offset, limit);
    }

    @Override
    public List<String> resourceIds(ResourceKind kind, int offset, int limit) {
        return mapper.resources(kind.name(), offset, limit);
    }

    private ProjectContextRow row(ProjectFlowContext context) {
        return new ProjectContextRow(context.id(), context.name(), context.userProfileId(), encode(context.assetIds()),
                encode(context.repositoryProfileIds()), encode(context.directionIds()), context.planId(),
                context.createdAt().toString(), context.updatedAt().toString(), context.version());
    }

    private ProjectFlowContext context(ProjectContextRow row) {
        return new ProjectFlowContext(row.id(), row.name(), row.userProfileId(), decode(row.assetIds()),
                decode(row.repositoryProfileIds()), decode(row.directionIds()), row.planId(),
                Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()), row.version());
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
