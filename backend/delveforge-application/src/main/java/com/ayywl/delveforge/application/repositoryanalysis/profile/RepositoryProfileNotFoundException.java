package com.ayywl.delveforge.application.repositoryanalysis.profile;

import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

/**
 * 按标识找不到 Repository Profile。
 *
 * <p>表示本次操作的目标快照不存在，而不是调用方输入格式错误，
 * 因此与 {@link IllegalArgumentException} 区分开。
 *
 * <p>该异常属于 Application 层语义，不由 Infrastructure 的技术异常（例如数据库
 * 查询失败）代替：技术异常应在 Adapter 边界翻译，不应泄漏到 Use Case 调用方。
 */
public class RepositoryProfileNotFoundException extends RuntimeException {

    public RepositoryProfileNotFoundException(RepositoryProfileId repositoryProfileId) {
        super("Repository Profile 不存在: " + repositoryProfileId.value());
    }
}
