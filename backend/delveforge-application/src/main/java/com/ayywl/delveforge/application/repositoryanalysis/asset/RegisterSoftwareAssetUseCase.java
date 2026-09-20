package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import java.util.UUID;

/**
 * 登记一个 Software Asset。
 *
 * <p>对应 DOMAIN_MODEL.md §14.7 中「SoftwareAsset 由用户指定」这一步：
 * 用户给出一个本地 Git Repository，系统为它建立稳定的资产身份，并保存其元数据。
 *
 * <h2>只登记元数据</h2>
 *
 * <p>本 Use Case 不访问文件系统，也不执行任何 Git 操作（RULE-ARCH-009）：
 * 它不判断 location 是否真实存在，也不判断它是否真的是一个 Git Repository。
 * 因此登记不需要 Workspace 能力，也不会因为路径不可访问而失败——
 * 只读访问 Repository 属于后续的 Analysis 流程。
 *
 * <p>这带来一个当前实现选择：不存在「这个位置其实不是 Git Repository」的领域错误，
 * 因为领域模型没有要求登记阶段做这种判断（§8.3 把「必须是可访问的本地 Git Repository」
 * 列为 Analyze Repository 的前置条件，而不是登记的前置条件）。
 *
 * <h2>不做的判断</h2>
 *
 * <pre>
 * 不检查 location 是否重复    参见 SoftwareAssetRepository 的说明
 * 不规范化 location 格式      领域模型把 location 的表现形式交给资产类型决定（§3.2）
 * 不校验用户是否真的拥有该目录  读取权限由输入显式给出，不由本 Use Case 推断
 * </pre>
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
                request.location(),
                request.readPermissionAllowed(),
                request.licenseInfo(),
                request.usageAuthorization());

        softwareAssetRepository.save(asset);
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
