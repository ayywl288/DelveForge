package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code user_profile_section_item} 表的 Mapper。
 *
 * <p>只被 {@link SqliteUserProfileRepository} 使用；Domain / Application 不得引用。
 */
public interface UserProfileSectionItemMapper extends BaseMapper<UserProfileSectionItemDO> {

    @Select("""
            SELECT DISTINCT old.section FROM user_profile_section_item old
            WHERE old.profile_id=#{id} AND old.revision < #{revision} AND NOT EXISTS (
                SELECT 1 FROM user_profile_section_item current
                WHERE current.profile_id=old.profile_id AND current.revision=#{revision}
                AND current.section=old.section AND current.value=old.value)
            ORDER BY old.section
            """)
    List<String> sectionsWithSupersededItems(@Param("id") String id, @Param("revision") int revision);
}
