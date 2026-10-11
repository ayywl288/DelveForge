package com.ayywl.delveforge.infrastructure.persistence.project;

import com.ayywl.delveforge.application.project.ProjectFlowContext;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ProjectContextMapper {
    @Select("SELECT plan_id, version, deleted_at FROM project_legacy_visibility WHERE plan_id = #{planId}")
    LegacyVisibilityRow legacyVisibility(String planId);

    @Select("SELECT COUNT(*) FROM evolution_plan WHERE id = #{planId}")
    int planExists(String planId);

    @Insert("""
            INSERT INTO project_legacy_visibility(plan_id, version, deleted_at)
            SELECT #{planId}, 0, NULL WHERE EXISTS (SELECT 1 FROM evolution_plan WHERE id = #{planId})
                AND NOT EXISTS (SELECT 1 FROM project_flow_context WHERE plan_id = #{planId})
            ON CONFLICT(plan_id) DO NOTHING
            """)
    int ensureLegacyVisibility(String planId);

    @Update("UPDATE project_flow_context SET version = version WHERE id = #{id} AND version = #{version}")
    int lockVisibility(@Param("id") String id, @Param("version") long version);

    @Update("""
            UPDATE project_legacy_visibility SET version = version WHERE plan_id = #{id} AND version = #{version}
                AND NOT EXISTS (SELECT 1 FROM project_flow_context WHERE plan_id = #{id})
            """)
    int lockLegacyVisibility(@Param("id") String id, @Param("version") long version);

    @Update("""
            UPDATE project_flow_context SET version = version + 1, deleted_at = #{deletedAt}, updated_at = #{now}
            WHERE id = #{id} AND version = #{version} AND (deleted_at IS NOT NULL) != #{deleted}
            """)
    int visibility(@Param("id") String id, @Param("version") long version, @Param("deleted") boolean deleted,
            @Param("deletedAt") String deletedAt, @Param("now") String now);

    @Update("""
            UPDATE project_legacy_visibility SET version = version + 1, deleted_at = #{deletedAt}
            WHERE plan_id = #{id} AND version = #{version} AND (deleted_at IS NOT NULL) != #{deleted}
            """)
    int legacyVisibilityUpdate(@Param("id") String id, @Param("version") long version,
            @Param("deleted") boolean deleted, @Param("deletedAt") String deletedAt);

    // READY 也可能有尚未持久化 ExecutionResult 的 Proposal 请求，不能当作空闲。
    @Select("""
            SELECT CASE WHEN #{planId} IS NOT NULL AND NOT EXISTS
                (SELECT 1 FROM evolution_plan WHERE id = #{planId}) THEN 1 ELSE 0 END
                + (SELECT COUNT(*) FROM evolution_plan p
                WHERE (p.id = #{planId} OR (#{flowId} IS NOT NULL AND p.flow_id = #{flowId})) AND (
                    p.status NOT IN ('PROPOSED','ACTIVE','COMPLETED','ABANDONED','SUPERSEDED')
                    OR EXISTS (SELECT 1 FROM evolution_step s WHERE s.plan_id = p.id
                        AND (s.status NOT IN ('PENDING_CONFIRMATION','SUCCEEDED','FAILED','CANCELLED')
                            OR (s.status = 'FAILED' AND s.execution_result IS NULL)
                            OR (s.status = 'SUCCEEDED' AND
                                (s.execution_result IS NULL OR s.verification_result IS NULL))
                            OR (s.execution_result IS NOT NULL AND NOT json_valid(s.execution_result))
                            OR (s.verification_result IS NOT NULL AND NOT json_valid(s.verification_result))))
                    OR (p.working_copy_id IS NOT NULL AND NOT EXISTS
                        (SELECT 1 FROM working_copy w WHERE w.id = p.working_copy_id
                            AND w.current_revision = w.last_verified_revision
                            AND w.status IN ('READY','EVOLVING','CLOSED')))))
            """)
    int unsafeExecutionCount(@Param("planId") String planId, @Param("flowId") String flowId);

    @Select("SELECT * FROM project_flow_context WHERE id = #{id}")
    ProjectContextRow find(String id);

    @Select("SELECT * FROM project_flow_context WHERE plan_id = #{planId}")
    ProjectContextRow findPlan(String planId);

    @Select("SELECT * FROM project_flow_context WHERE flow_id = #{flowId} ORDER BY created_at, id LIMIT 100")
    List<ProjectContextRow> findFlow(String flowId);

    @Update("""
            UPDATE project_flow_context SET version = version + 1, updated_at = #{updatedAt}
            WHERE id = #{basis.id} AND version = #{basis.version} AND flow_id = #{basis.flowId} AND deleted_at IS NULL
                AND plan_id = #{basis.planId}
                AND EXISTS (SELECT 1 FROM evolution_plan p WHERE p.id = plan_id
                    AND p.flow_id = #{basis.flowId} AND p.status = 'SUPERSEDED'
                    AND NOT EXISTS (SELECT 1 FROM evolution_step s WHERE s.plan_id = p.id
                        AND s.status IN ('EXECUTING','VERIFYING','RECOVERY_REQUIRED','VERIFICATION_FAILED','ROLLING_BACK')))
                AND NOT EXISTS (SELECT 1 FROM project_flow_context c WHERE c.previous_context_id = #{basis.id})
            """)
    int lockSuccessor(@Param("basis") ProjectFlowContext basis,
            @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO project_flow_context
                (id, name, user_profile_id, asset_ids, repository_profile_ids, direction_ids,
                 plan_id, created_at, updated_at, version, flow_id, previous_context_id)
            SELECT #{row.id}, #{row.name}, #{row.userProfileId}, #{row.assetIds}, #{row.repositoryProfileIds},
                #{row.directionIds}, #{row.planId}, #{row.createdAt}, #{row.updatedAt}, #{row.version},
                #{row.flowId}, #{row.previousContextId}
            WHERE NOT EXISTS (SELECT 1 FROM project_legacy_visibility v
                    WHERE v.plan_id = #{row.planId} AND v.deleted_at IS NOT NULL)
                AND (#{revision} IS NULL OR EXISTS (SELECT 1 FROM user_profile
                WHERE id = #{row.userProfileId} AND status = 'CONFIRMED' AND revision = #{revision})
                )
            ON CONFLICT(plan_id) DO NOTHING
            """)
    int insert(@Param("row") ProjectContextRow row, @Param("revision") Integer revision);

    @Update("""
            UPDATE project_flow_context SET user_profile_id = #{row.userProfileId}, asset_ids = #{row.assetIds},
                repository_profile_ids = #{row.repositoryProfileIds}, direction_ids = #{row.directionIds},
                plan_id = #{row.planId}, updated_at = #{row.updatedAt}, version = #{row.version}
            WHERE id = #{row.id} AND version = #{expected} AND flow_id = #{row.flowId} AND deleted_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM json_each(#{row.directionIds}) j
                    LEFT JOIN product_direction d ON d.id = j.value
                    WHERE d.id IS NULL OR d.flow_id IS NOT #{row.flowId})
                AND (#{row.planId} IS NULL OR EXISTS (SELECT 1 FROM evolution_plan p
                    WHERE p.id = #{row.planId} AND p.flow_id = #{row.flowId}))
                AND (user_profile_id IS NULL OR user_profile_id = #{row.userProfileId})
                AND (plan_id IS NULL OR plan_id = #{row.planId})
                AND (#{row.planId} IS NULL OR NOT EXISTS (SELECT 1 FROM project_flow_context
                    WHERE plan_id = #{row.planId} AND id <> #{row.id}))
            """)
    int update(@Param("row") ProjectContextRow row, @Param("expected") long expected);

    @Select("""
            SELECT project_key FROM (
                SELECT 'project:' || id AS project_key, updated_at AS activity FROM project_flow_context
                    WHERE (deleted_at IS NOT NULL) = #{deleted}
                UNION ALL
                SELECT 'plan:' || p.id AS project_key,
                    '' AS activity
                FROM evolution_plan p LEFT JOIN project_legacy_visibility v ON v.plan_id = p.id
                WHERE NOT EXISTS (SELECT 1 FROM project_flow_context c WHERE c.plan_id = p.id)
                    AND (v.deleted_at IS NOT NULL) = #{deleted}
            ) ORDER BY activity DESC, project_key ASC LIMIT #{limit} OFFSET #{offset}
            """)
    List<String> history(@Param("offset") int offset, @Param("limit") int limit, @Param("deleted") boolean deleted);

    @Select("""
            <script>
            SELECT id FROM
            <choose>
                <when test="kind == 'USER_PROFILE'">user_profile</when>
                <when test="kind == 'SOFTWARE_ASSET'">software_asset</when>
                <otherwise>repository_profile</otherwise>
            </choose>
            ORDER BY id LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<String> resources(@Param("kind") String kind, @Param("offset") int offset, @Param("limit") int limit);
}
