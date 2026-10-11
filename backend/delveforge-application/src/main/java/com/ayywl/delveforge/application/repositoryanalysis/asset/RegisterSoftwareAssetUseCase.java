package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;

import java.util.UUID;

/**
 * 登记用户显式指定的本地 Git 仓库，不读取源码或使用 AI。
 * Repository 通过只读 LocalRepositoryIdentity 比较位置，并发拒绝重复登记。
 * 不扫描许可证，不覆盖既有授权，也不要求当前位置已经是 Git 仓库。
 */
public class RegisterSoftwareAssetUseCase {

    private final SoftwareAssetRepository softwareAssetRepository;

    public RegisterSoftwareAssetUseCase(SoftwareAssetRepository softwareAssetRepository) {
        if (softwareAssetRepository == null) {
            throw new IllegalArgumentException(
                    "RegisterSoftwareAssetUseCase 必须指定 softwareAssetRepository");
        }
        this.softwareAssetRepository = softwareAssetRepository;
    }

    /**
     * 登记并保存一个新的 Software Asset。
     *
     * <p>资产的初始状态完全由 Domain 决定：本方法只把输入映射为 Aggregate 的
     * {@code create} 调用，不参与任何领域判断。
     *
     * @param request 登记输入，不得为 {@code null}
     * @return 已保存的资产，其标识由本次登记生成
     * @throws IllegalArgumentException 输入缺失或取值不合法（由 Aggregate 判定）
     */
    public SoftwareAsset register(RegisterSoftwareAssetRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("登记 Software Asset 必须提供输入");
        }

        SoftwareAsset asset = SoftwareAsset.create(
                generateAssetId(),
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                request.location() == null ? null : request.location().trim(),
                request.readPermissionAllowed(),
                request.licenseInfo(),
                request.usageAuthorization());

        softwareAssetRepository.registerUnique(asset);
        return asset;
    }

    /**
     * 生成新资产的标识。
     *
     * <p>标识由 Application 在登记时分配，不由调用方提供，与
     * {@code CreateUserProfileUseCase} 保持一致：调用方描述「要登记什么资产」，
     * 而不是「这个资产的 id 是什么」。
     */
    private static SoftwareAssetId generateAssetId() {
        return new SoftwareAssetId(UUID.randomUUID().toString());
    }
}
