package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

/**
 * 按标识找不到 Software Asset。
 *
 * <p>表示本次操作的目标资产不存在，而不是调用方输入格式错误，
 * 因此与 {@link IllegalArgumentException} 区分开。
 *
 * <p>该异常属于 Application 层语义，不由 Infrastructure 的技术异常（例如数据库
 * 查询失败）代替：技术异常应在 Adapter 边界翻译，不应泄漏到 Use Case 调用方。
 *
 * <p>Interface 层需要为该异常定义对外映射；当前尚无 REST API，因此尚未映射。
 */
public class SoftwareAssetNotFoundException extends RuntimeException {

    public SoftwareAssetNotFoundException(SoftwareAssetId softwareAssetId) {
        super("Software Asset 不存在: " + softwareAssetId.value());
    }
}
