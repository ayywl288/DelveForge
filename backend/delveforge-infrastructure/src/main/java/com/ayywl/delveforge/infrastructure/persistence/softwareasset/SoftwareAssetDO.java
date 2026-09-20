package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code software_asset} 表的数据对象。
 *
 * <p>只表示一个 Software Asset 的元数据，不表示它的源代码。
 *
 * <p>一个资产对应一行：本 Task 采用覆盖保存，因此没有「当前状态 + 历史快照」的划分。
 * 这是实现选择而不是领域模型的规定，见 {@code SoftwareAssetRepository#save}。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象，
 * 不得出现在 Domain / Application 中。
 */
@TableName("software_asset")
public class SoftwareAssetDO {

    /** 身份由 Domain 的 {@code SoftwareAssetId} 提供，不由数据库生成。 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String type;

    private String source;

    private String location;

    /** 以 0 / 1 保存：0 表示不允许读取，1 表示允许。 */
    private Integer readPermission;

    /**
     * 允许为 {@code null}，表示当前不知道该资产的许可证信息。
     *
     * <p>更新策略必须显式设为 {@link FieldStrategy#ALWAYS}：MyBatis-Plus 默认只在字段
     * 非 null 时才把该列放进 {@code UPDATE} 的 SET 子句。沿用默认策略时，
     * 「已知许可证 → 未知」的覆盖保存不会清空该列，重新读回会得到旧许可证，
     * 与调用方刚刚保存的 Aggregate 不一致。
     *
     * <p>这里只单独放开本字段，不修改全局更新策略：其余列都由领域保证非 null，
     * 沿用默认策略不会丢值。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String licenseInfo;

    private String usageAuthorization;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getReadPermission() {
        return readPermission;
    }

    public void setReadPermission(Integer readPermission) {
        this.readPermission = readPermission;
    }

    public String getLicenseInfo() {
        return licenseInfo;
    }

    public void setLicenseInfo(String licenseInfo) {
        this.licenseInfo = licenseInfo;
    }

    public String getUsageAuthorization() {
        return usageAuthorization;
    }

    public void setUsageAuthorization(String usageAuthorization) {
        this.usageAuthorization = usageAuthorization;
    }
}
