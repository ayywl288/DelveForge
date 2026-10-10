package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code user_profile} 表的 Mapper。
 *
 * <p>只被 {@link SqliteUserProfileRepository} 使用；Domain / Application 不得引用。
 */
public interface UserProfileMapper extends BaseMapper<UserProfileDO> {

    @Update("UPDATE user_profile SET status = status WHERE id = #{id} "
            + "AND revision = #{revision} AND status = #{status}")
    int lockCurrent(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);

    @Insert("INSERT INTO user_profile_sufficiency(profile_id, revision) VALUES(#{id}, #{revision}) "
            + "ON CONFLICT(profile_id) DO UPDATE SET revision = excluded.revision")
    void recordSufficient(@Param("id") String id, @Param("revision") int revision);

    @Delete("DELETE FROM user_profile_sufficiency WHERE profile_id = #{id} AND revision <> #{revision}")
    void invalidateSufficiency(@Param("id") String id, @Param("revision") int revision);

    @Select("SELECT COUNT(*) FROM user_profile_sufficiency s JOIN user_profile p ON p.id = s.profile_id "
            + "WHERE s.profile_id = #{id} AND s.revision = #{revision} AND p.revision = s.revision")
    int sufficientCount(@Param("id") String id, @Param("revision") int revision);
}
