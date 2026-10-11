package com.ayywl.delveforge.infrastructure.persistence.project;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ProjectContextMapper {
    @Select("SELECT * FROM project_flow_context WHERE id = #{id}")
    ProjectContextRow find(String id);

    @Select("SELECT * FROM project_flow_context WHERE plan_id = #{planId}")
    ProjectContextRow findPlan(String planId);

    @Select("SELECT * FROM project_flow_context WHERE flow_id = #{flowId} ORDER BY created_at, id LIMIT 100")
    List<ProjectContextRow> findFlow(String flowId);

    @Update("""
            UPDATE project_flow_context SET version = version + 1, updated_at = #{updatedAt}
            WHERE id = #{basis.id} AND version = #{basis.version} AND flow_id = #{basis.flowId}
                AND plan_id = #{basis.planId}
                AND EXISTS (SELECT 1 FROM evolution_plan p WHERE p.id = plan_id
                    AND p.flow_id = #{basis.flowId} AND p.status = 'SUPERSEDED'
                    AND NOT EXISTS (SELECT 1 FROM evolution_step s WHERE s.plan_id = p.id
                        AND s.status IN ('EXECUTING','VERIFYING','RECOVERY_REQUIRED','VERIFICATION_FAILED','ROLLING_BACK')))
                AND NOT EXISTS (SELECT 1 FROM project_flow_context c WHERE c.previous_context_id = #{basis.id})
            """)
    int lockSuccessor(@Param("basis") com.ayywl.delveforge.application.project.ProjectFlowContext basis,
            @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO project_flow_context
                (id, name, user_profile_id, asset_ids, repository_profile_ids, direction_ids,
                 plan_id, created_at, updated_at, version, flow_id, previous_context_id)
            SELECT #{row.id}, #{row.name}, #{row.userProfileId}, #{row.assetIds}, #{row.repositoryProfileIds},
                #{row.directionIds}, #{row.planId}, #{row.createdAt}, #{row.updatedAt}, #{row.version},
                #{row.flowId}, #{row.previousContextId}
            WHERE #{revision} IS NULL OR EXISTS (SELECT 1 FROM user_profile
                WHERE id = #{row.userProfileId} AND status = 'CONFIRMED' AND revision = #{revision})
            ON CONFLICT(plan_id) DO NOTHING
            """)
    int insert(@Param("row") ProjectContextRow row, @Param("revision") Integer revision);

    @Update("""
            UPDATE project_flow_context SET user_profile_id = #{row.userProfileId}, asset_ids = #{row.assetIds},
                repository_profile_ids = #{row.repositoryProfileIds}, direction_ids = #{row.directionIds},
                plan_id = #{row.planId}, updated_at = #{row.updatedAt}, version = #{row.version}
            WHERE id = #{row.id} AND version = #{expected} AND flow_id = #{row.flowId}
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
                UNION ALL
                SELECT 'plan:' || p.id AS project_key,
                    '' AS activity
                FROM evolution_plan p WHERE NOT EXISTS (SELECT 1 FROM project_flow_context c WHERE c.plan_id = p.id)
            ) ORDER BY activity DESC, project_key ASC LIMIT #{limit} OFFSET #{offset}
            """)
    List<String> history(@Param("offset") int offset, @Param("limit") int limit);

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
