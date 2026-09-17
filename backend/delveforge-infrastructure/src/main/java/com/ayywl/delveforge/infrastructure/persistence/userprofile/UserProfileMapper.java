package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code user_profile} 表的 Mapper。
 *
 * <p>只被 {@link SqliteUserProfileRepository} 使用；Domain / Application 不得引用。
 */
public interface UserProfileMapper extends BaseMapper<UserProfileDO> {
}
