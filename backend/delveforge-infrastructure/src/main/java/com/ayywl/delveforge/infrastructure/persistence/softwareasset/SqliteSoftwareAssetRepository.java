package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetAlreadyRegisteredException;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.LocalRepositoryIdentity;
import com.ayywl.delveforge.domain.asset.AssetAuthorizationConflictException;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * SQLite 资产元数据：新登记串行比较只读路径身份；授权字段按版本 CAS 更新。
 * 历史重复身份保留。位置解析通过 Workspace Port，不读取源码或推断许可。
 * save 仅保留历史数据兼容能力，注册 / 更新 Use Case 使用各自的条件写入入口。
 */
@Repository
public class SqliteSoftwareAssetRepository implements SoftwareAssetRepository {

    /** {@code read_permission} 的存储取值：SQLite 没有原生 boolean 类型。 */
    private static final int READ_PERMISSION_DENIED = 0;

    private static final int READ_PERMISSION_ALLOWED = 1;

    private final SoftwareAssetMapper softwareAssetMapper;
    private final LocalRepositoryIdentity identity;

    public SqliteSoftwareAssetRepository(SoftwareAssetMapper softwareAssetMapper, LocalRepositoryIdentity identity) {
        this.softwareAssetMapper = softwareAssetMapper;
        this.identity = identity;
    }

    @Override
    @Transactional
    public void registerUnique(SoftwareAsset asset) {
        String key = identity.key(asset.location(), false);
        // 写锁先于读取，避免两个 DEFERRED 事务同时判定不存在。
        if (softwareAssetMapper.lockRegistration() != 1) {
            throw new IllegalStateException("Registration guard is missing");
        }
        if (softwareAssetMapper.selectList(null).stream()
                .anyMatch(row -> identity.key(row.getLocation(), true).equals(key))) {
            throw new SoftwareAssetAlreadyRegisteredException();
        }
        softwareAssetMapper.insert(toRow(asset));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SoftwareAsset> findByLocation(String location) {
        String key = identity.key(location, false);
        return softwareAssetMapper.selectList(null).stream()
                .filter(row -> identity.key(row.getLocation(), true).equals(key))
                .map(SqliteSoftwareAssetRepository::fromRow)
                .sorted(java.util.Comparator.comparing(asset -> asset.id().value()))
                .toList();
    }

    @Override
    @Transactional
    public void updateAuthorization(SoftwareAsset candidate, long expectedRevision) {
        if (candidate.authorizationRevision() != Math.addExact(expectedRevision, 1)) {
            throw new IllegalArgumentException("Authorization candidate must advance one revision");
        }
        if (softwareAssetMapper.updateAuthorization(toRow(candidate), expectedRevision) != 1) {
            throw new AssetAuthorizationConflictException();
        }
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
        return Optional.of(fromRow(row));
    }

    private static SoftwareAsset fromRow(SoftwareAssetDO row) {
        return SoftwareAsset.reconstitute(
                new SoftwareAssetId(row.getId()),
                SoftwareAssetType.valueOf(row.getType()),
                SoftwareAssetSource.valueOf(row.getSource()),
                row.getLocation(),
                row.getReadPermission() != READ_PERMISSION_DENIED,
                row.getLicenseInfo(),
                UsageAuthorization.valueOf(row.getUsageAuthorization()), row.getAuthorizationRevision());
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
        row.setAuthorizationRevision(asset.authorizationRevision());
        return row;
    }
}
