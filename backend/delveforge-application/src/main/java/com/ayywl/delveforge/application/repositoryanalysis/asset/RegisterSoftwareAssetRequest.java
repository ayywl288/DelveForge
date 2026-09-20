package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.domain.asset.UsageAuthorization;

/**
 * 一次 Software Asset 登记的结构化输入。
 *
 * <p>输入已经是结构化数据：从用户选择本地 Repository 到本类型的转换
 * （交互流程、路径如何取得）不属于本层职责。
 *
 * <h2>为什么读取权限与使用授权必须由调用方显式给出</h2>
 *
 * <p>两者都不在这里提供默认值，也不由 {@link RegisterSoftwareAssetUseCase} 代为假定：
 * DOMAIN_MODEL.md §3.2 把它们定义为资产自身「当前」的事实，Aggregate 也刻意
 * 不提供「未说明时默认允许」。若本层给出默认值，等于替用户制造了一个资产授权事实
 * （RULE-DOM-004、RULE-DOM-008）。
 *
 * <p>因此一个尚未确认使用授权的资产，必须由调用方显式给出
 * {@link UsageAuthorization#UNCLEAR}，而不是省略该字段。
 *
 * <h2>类型与来源</h2>
 *
 * <p>输入不包含 type 与 source：MVP 中 Software Asset 只能是用户指定的本地
 * Git Repository（§3.2、§14.7），由 Use Case 固定。扩展到其他类型或来源时，
 * 再让本输入显式携带它们。
 *
 * @param location             能够定位该资产的引用；MVP 中为用户指定的本地 Repository 位置。
 *                             其格式由资产类型决定（§3.2），本层不做路径规范化或存在性判断
 * @param readPermissionAllowed 当前是否允许系统读取和分析该资产（§3.2 的 readPermission）
 * @param licenseInfo          已知的软件许可证信息；{@code null} 表示当前未知
 * @param usageAuthorization   当前的使用授权状态（§3.2 的 usageAuthorization），不得为 {@code null}
 */
public record RegisterSoftwareAssetRequest(
        String location,
        boolean readPermissionAllowed,
        String licenseInfo,
        UsageAuthorization usageAuthorization) {

    // 不在构造器内做取值校验：location 是否合法、usageAuthorization 是否为 null
    // 由 SoftwareAsset Aggregate 判定（RULE-DOM-002），本类型只承载输入。
}
