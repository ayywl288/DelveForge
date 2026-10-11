package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.ayywl.delveforge.application.project.ProjectScopeBasis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code product_direction} 表的 Mapper。
 *
 * <p>只被 {@link SqliteProductDirectionRepository} 使用；Domain / Application 不得引用。
 */
public interface ProductDirectionMapper extends BaseMapper<ProductDirectionDO> {
    @Select("SELECT flow_id FROM product_direction WHERE id = #{id}")
    String flow(String id);

    @Update("""
            UPDATE project_flow_context SET version = version WHERE id = #{project.contextId}
                AND version = #{project.expectedVersion} AND flow_id IS NOT NULL AND deleted_at IS NULL
            """)
    int lockProject(@Param("project")
            ProjectScopeBasis project);

    @Select("""
            SELECT flow_id FROM project_flow_context WHERE id = #{project.contextId}
                AND version = #{project.expectedVersion} AND user_profile_id = #{userId} AND deleted_at IS NULL
            """)
    String discoveryFlow(@Param("project")
            ProjectScopeBasis project,
            @Param("userId") String userId);

    @Select("""
            SELECT COUNT(*) FROM project_flow_context c, json_each(c.repository_profile_ids) j
            JOIN repository_profile r ON r.id = j.value
            WHERE c.id = #{contextId} AND j.value = #{repositoryId} AND c.deleted_at IS NULL
                AND EXISTS (SELECT 1 FROM json_each(c.asset_ids) a WHERE a.value = r.asset_id)
            """)
    int containsRepository(@Param("contextId") String contextId,
            @Param("repositoryId") String repositoryId);

    @Select("""
            SELECT COUNT(*) FROM project_flow_context c JOIN product_direction d ON d.flow_id = c.flow_id
            WHERE c.id = #{project.contextId} AND c.version = #{project.expectedVersion} AND d.id = #{directionId}
                AND c.user_profile_id = d.user_profile_id AND c.deleted_at IS NULL
            """)
    int containsDirection(@Param("project")
            ProjectScopeBasis project,
            @Param("directionId") String directionId);

    @Select("""
            SELECT COUNT(*) FROM user_profile WHERE id = #{userId} AND revision = #{revision} AND status = 'CONFIRMED'
            """)
    int currentConfirmed(@Param("userId") String userId,
            @Param("revision") int revision);

    @Select("""
            SELECT COUNT(*) FROM project_flow_context WHERE id = #{project.contextId}
                AND version = #{project.expectedVersion} AND flow_id IS NOT NULL AND deleted_at IS NULL
                AND plan_id IS NULL
            """)
    int currentPlanningContext(@Param("project") ProjectScopeBasis project);
}
