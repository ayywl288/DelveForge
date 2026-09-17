package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code user_profile_revision} 表的 Mapper：已保存版本的锚点。
 *
 * <p>该表的主键是 {@code (profile_id, revision)} 复合主键，不符合 MyBatis-Plus
 * {@code BaseMapper} 围绕单一 {@code @TableId} 的模型——把其中一列标成主键会
 * 误导后续调用者使用 {@code deleteById} 一类方法，从而按 profile 删除全部版本。
 * 因此这里直接声明需要的三条 SQL，只暴露本 Adapter 实际使用的操作。
 *
 * <p>只被 {@link SqliteUserProfileRepository} 使用；Domain / Application 不得引用。
 */
@Mapper
public interface UserProfileRevisionMapper {

    /** 记录一个已保存的版本；调用方需保证同一 (profileId, revision) 尚未存在。 */
    @Insert("INSERT INTO user_profile_revision (profile_id, revision) VALUES (#{profileId}, #{revision})")
    int insert(@Param("profileId") String profileId, @Param("revision") int revision);

    @Delete("DELETE FROM user_profile_revision WHERE profile_id = #{profileId} AND revision = #{revision}")
    int delete(@Param("profileId") String profileId, @Param("revision") int revision);

    /** 该 version 是否已被保存过；不依赖子表是否存在内容行。 */
    @Select("SELECT COUNT(*) FROM user_profile_revision "
            + "WHERE profile_id = #{profileId} AND revision = #{revision}")
    int count(@Param("profileId") String profileId, @Param("revision") int revision);
}
