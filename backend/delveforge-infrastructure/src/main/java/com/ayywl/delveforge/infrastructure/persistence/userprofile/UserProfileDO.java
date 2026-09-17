package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code user_profile} 表的数据对象。
 *
 * <p>只表示一位 User Profile 的身份与当前状态。六个内容区与 Evidence 按
 * {@code (profile_id, revision)} 保存在子表中，见 {@link UserProfileSectionItemDO}
 * 与 {@link UserProfileEvidenceDO}。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象，
 * 不得出现在 Domain / Application 中。
 */
@TableName("user_profile")
public class UserProfileDO {

    /** 身份由 Domain 的 {@code UserProfileId} 提供，不由数据库生成。 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String status;

    private Integer revision;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }
}
