package com.ayywl.delveforge.app.api.softwareasset;

import com.ayywl.delveforge.domain.asset.UsageAuthorization;

/**
 * 注册 Software Asset 的请求体。
 *
 * <p>只包含注册时由用户给出的事实。资产的标识、类型、来源都不在这里：
 * 标识由服务端生成，类型与来源由 MVP 固定（本地 Git Repository，用户指定），
 * 客户端无法通过请求体改写它们。
 *
 * <p>读取权限与使用授权必须显式给出（DOMAIN_MODEL.md §3.2 把它们定义为资产自身的
 * 事实），因此接口层不提供默认值，也不替调用方推断。
 *
 * <p>{@code readPermissionAllowed} 用包装类型而不是 {@code boolean}，正是为了让
 * 「没给」与「给了 false」在类型上可区分：原始类型会把缺失的字段静默变成 {@code false}，
 * 那等于替用户声明了一个「不允许读取」的授权事实。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param location              能够定位该资产的引用；MVP 中为本地 Repository 的位置
 * @param readPermissionAllowed 当前是否允许系统读取和分析该资产；必须显式给出
 * @param licenseInfo           已知的软件许可证信息；{@code null} 表示当前未知
 * @param usageAuthorization    当前的使用授权状态；必须显式给出，且只能用取值名称
 */
public record SoftwareAssetRegistrationRequest(
        String location,
        Boolean readPermissionAllowed,
        String licenseInfo,
        UsageAuthorization usageAuthorization) {
}
