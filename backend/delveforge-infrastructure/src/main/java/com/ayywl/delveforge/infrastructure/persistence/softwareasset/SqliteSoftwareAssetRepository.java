package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SoftwareAssetRepository} 的 SQLite / MyBatis-Plus 实现。
 *
 * <p>SQLite、MyBatis-Plus 与持久化数据对象只出现在本包内；Domain 与 Application
 * 只看到 {@link SoftwareAssetRepository} 与领域对象（RULE-ARCH-002、RULE-ARCH-003）。
 *
 * <h2>存储结构</h2>
 *
 * <pre>
 * software_asset   身份 + 类型 + 来源 + 定位 + 读取权限 + 许可证信息 + 使用授权
 * </pre>
 *
 * <p>单表保存：本 Task 采用覆盖保存，因此没有子表，也没有 {@code user_profile}
 * 那样的版本快照划分。这是当前的实现选择，不是领域模型的规定
 * （见 {@link SoftwareAssetRepository#save}）。
 *
 * <h2>写入规则</h2>
 *
 * <p>按标识覆盖：同一 {@code id} 的重复保存更新同一行，不产生第二行。
 * Adapter 不做去重判断——location 相同不代表是同一个 Software Asset，
 * 领域模型没有定义 location 唯一性。
 *
 * <p>不在此处校验领域规则：资产是否合法由 {@code SoftwareAsset} Aggregate 决定
 * （RULE-DOM-002），本类只负责把它的当前状态写下去、按存储重建回来。
 */
@Repository
public class SqliteSoftwareAssetRepository implements SoftwareAssetRepository {

    /** {@code read_permission} 的存储取值：SQLite 没有原生 boolean 类型。 */
    private static final int READ_PERMISSION_DENIED = 0;

    private static final int READ_PERMISSION_ALLOWED = 1;

    private final SoftwareAssetMapper softwareAssetMapper;

    public SqliteSoftwareAssetRepository(SoftwareAssetMapper softwareAssetMapper) {
        this.softwareAssetMapper = softwareAssetMapper;
    }

    @Override
    @Transactional
    public void save(SoftwareAsset asset) {
        SoftwareAssetDO row = toRow(asset);
        if (softwareAssetMapper.selectById(row.getId()) == null) {
            softwareAssetMapper.insert(row);
        } else {
            softwareAssetMapper.updateById(row);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SoftwareAsset> findById(SoftwareAssetId id) {
        SoftwareAssetDO row = softwareAssetMapper.selectById(id.value());
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(SoftwareAsset.reconstitute(
                new SoftwareAssetId(row.getId()),
                SoftwareAssetType.valueOf(row.getType()),
                SoftwareAssetSource.valueOf(row.getSource()),
                row.getLocation(),
                row.getReadPermission() != READ_PERMISSION_DENIED,
                row.getLicenseInfo(),
                UsageAuthorization.valueOf(row.getUsageAuthorization())));
    }

    private static SoftwareAssetDO toRow(SoftwareAsset asset) {
        SoftwareAssetDO row = new SoftwareAssetDO();
        row.setId(asset.id().value());
        row.setType(asset.type().name());
        row.setSource(asset.source().name());
        row.setLocation(asset.location());
        row.setReadPermission(asset.readPermissionAllowed()
                ? READ_PERMISSION_ALLOWED
                : READ_PERMISSION_DENIED);
        row.setLicenseInfo(asset.licenseInfo().orElse(null));
        row.setUsageAuthorization(asset.usageAuthorization().name());
        return row;
    }
}
