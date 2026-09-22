package com.ayywl.delveforge.app.api.softwareasset;

import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;

/**
 * Software Asset 在 HTTP 接口上的表示。
 *
 * <p>{@code type}、{@code source}、{@code usageAuthorization} 使用 Domain 的取值，
 * 不引入接口层同义词（RULE-DOM-001）。未知取值会在反序列化阶段失败，
 * 并由统一错误映射翻译为 {@code INVALID_REQUEST}。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param id                    资产标识
 * @param type                  资产类型
 * @param source                资产来源
 * @param location              能够定位该资产的引用
 * @param readPermissionAllowed 当前是否允许系统读取和分析该资产
 * @param licenseInfo           已知的软件许可证信息；{@code null} 表示当前未知
 * @param usageAuthorization    当前的使用授权状态
 */
public record SoftwareAssetResponse(
        String id,
        SoftwareAssetType type,
        SoftwareAssetSource source,
        String location,
        boolean readPermissionAllowed,
        String licenseInfo,
        UsageAuthorization usageAuthorization) {
}
