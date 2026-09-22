package com.ayywl.delveforge.application.repositoryanalysis.profile;

import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

/**
 * 读取一个已保存的 Repository Profile。
 *
 * <p>读到的就是那一次分析的快照本身：Profile 不随后续变化更新，因此这里不需要
 * 「取最新一条」之类的语义，也不提供按资产或 revision 查询——当前没有这样的消费者。
 *
 * <p>读取本身不做编排，因此这里只负责把「找不到」翻译成明确的失败语义，
 * 让调用方不必自行判断 {@code Optional}。它不接触 Workspace，也不调用 AI。
 */
public class GetRepositoryProfileUseCase {

    private final RepositoryProfileRepository repositoryProfileRepository;

    public GetRepositoryProfileUseCase(RepositoryProfileRepository repositoryProfileRepository) {
        if (repositoryProfileRepository == null) {
            throw new IllegalArgumentException(
                    "GetRepositoryProfileUseCase 必须指定 repositoryProfileRepository");
        }
        this.repositoryProfileRepository = repositoryProfileRepository;
    }

    /**
     * 读取指定的 Repository Profile。
     *
     * @param repositoryProfileId 快照标识
     * @return 该次分析的结果
     * @throws RepositoryProfileNotFoundException 该快照不存在
     */
    public RepositoryProfile get(RepositoryProfileId repositoryProfileId) {
        return repositoryProfileRepository.findById(repositoryProfileId)
                .orElseThrow(() -> new RepositoryProfileNotFoundException(repositoryProfileId));
    }
}
