package com.ayywl.delveforge.infrastructure.persistence.repositoryprofile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code repository_profile} 表的数据对象。
 *
 * <p>只表示一次分析快照的身份与它描述的对象。六个分析内容区与 Evidence 保存在子表中，
 * 见 {@link RepositoryProfileSectionItemDO} 与 {@link RepositoryProfileEvidenceDO}。
 *
 * <p>没有 revision 与 status 列：Repository Profile 不定义状态机，它自己就是
 * {@code analyzed_revision} 所指向的那个软件状态的一次快照。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象，
 * 不得出现在 Domain / Application 中。
 */
@TableName("repository_profile")
public class RepositoryProfileDO {

    /** 身份由 Domain 的 {@code RepositoryProfileId} 提供，不由数据库生成。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 被分析的 Software Asset，跨 Aggregate 引用，只保留身份。 */
    private String assetId;

    private String analyzedRevision;

    private String purpose;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getAnalyzedRevision() {
        return analyzedRevision;
    }

    public void setAnalyzedRevision(String analyzedRevision) {
        this.analyzedRevision = analyzedRevision;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }
}
