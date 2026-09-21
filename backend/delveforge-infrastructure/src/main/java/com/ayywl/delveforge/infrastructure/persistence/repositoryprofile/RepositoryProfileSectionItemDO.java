package com.ayywl.delveforge.infrastructure.persistence.repositoryprofile;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code repository_profile_section_item} 表的数据对象：一次分析中某个内容区的一项内容。
 *
 * <p>{@code section} 取领域字段名（techStack / modules / capabilities /
 * reusableAssets / limitations / risks），{@code position} 保留列表顺序。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("repository_profile_section_item")
public class RepositoryProfileSectionItemDO {

    private String profileId;

    private String section;

    private Integer position;

    private String value;

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
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
