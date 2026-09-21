package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

/**
 * 该 Repository Profile 已经保存过了。
 *
 * <p>一次分析对应一个 Snapshot，因此同一个标识只允许写入一次。再次保存同一个
 * {@code RepositoryProfileId} 会被拒绝，而不是覆盖已有快照：
 * 历史 Product Direction 或 Evolution Plan 可能仍然引用它（DOMAIN_MODEL.md §10.4、
 * INV-D04）。
 *
 * <p>这是业务冲突而不是技术故障：请求本身可以理解，只是与已经保存的领域状态冲突。
 * 因此它有自己的类型，而不是借用 JDK 的通用异常——后者会让 Interface 层无法只把
 * 这一类冲突映射成对应的协议错误（AGENTS.md §8.7）。
 *
 * <p>注意：{@code UserProfileRepository} 目前仍以 {@code IllegalStateException} 表达
 * 类似的写入冲突，那是 M1 记录的遗留项；本 Port 是本 Task 新建的，因此直接采用
 * 明确的类型，两者暂不一致。
 */
public class RepositoryProfileAlreadyExistsException extends RuntimeException {

    public RepositoryProfileAlreadyExistsException(RepositoryProfileId repositoryProfileId) {
        super("Repository Profile 已存在，不允许覆盖已有快照: " + repositoryProfileId.value());
    }
}
