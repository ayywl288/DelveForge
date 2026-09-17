package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code user_profile_section_item} 表的数据对象：某个 revision 下一个内容区的一项内容。
 *
 * <p>{@code section} 取领域字段名，{@code position} 保留列表顺序。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("user_profile_section_item")
public class UserProfileSectionItemDO {

    private String profileId;

    private Integer revision;

    private String section;

    private Integer position;

    private String value;

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
