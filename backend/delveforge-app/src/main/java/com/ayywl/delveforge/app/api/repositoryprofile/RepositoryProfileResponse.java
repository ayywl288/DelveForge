package com.ayywl.delveforge.app.api.repositoryprofile;

import com.ayywl.delveforge.app.api.evidence.EvidencePayload;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import java.util.List;

/**
 * Repository Profile 在 HTTP 接口上的表示：某个软件资产在确定 revision 下的一次分析快照。
 *
 * <p>{@code analyzedRevision} 由服务端在分析时解析并固定，客户端无法指定；
 * 它与下面的分析内容一起构成同一个快照，两者不得分别变化。
 *
 * <p>{@code evidence} 的 {@code sourceRef} 是 Repository 内相对于根目录的路径，
 * 而不是宿主机的绝对路径——接口不暴露宿主机文件系统布局。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param id               快照标识
 * @param assetId          被分析的软件资产
 * @param analyzedRevision 本次分析对应的软件状态
 * @param purpose          该 Repository 当前解决的问题或主要用途
 * @param techStack        主要技术栈
 * @param modules          主要业务或技术模块
 * @param capabilities     当前已经具备的核心能力
 * @param reusableAssets   具有直接复用或演化价值的能力、模块或实现
 * @param limitations      当前项目的重要限制
 * @param risks            对后续演化可能产生影响的技术风险
 * @param evidence         支撑以上判断的依据
 */
public record RepositoryProfileResponse(
        String id,
        String assetId,
        String analyzedRevision,
        String purpose,
        List<String> techStack,
        List<String> modules,
        List<String> capabilities,
        List<String> reusableAssets,
        List<String> limitations,
        List<String> risks,
        List<EvidencePayload> evidence) {

    /**
     * 把领域快照映射为它的接口表示。
     *
     * <p>两个端点都返回本资源：分析端点（在 Software Asset 路径下）返回新形成的快照，
     * 查询端点返回已保存的快照。映射因此放在资源自己的类上，
     * 而不是让其中一个 Controller 提供另一个资源要用的静态方法。
     */
    public static RepositoryProfileResponse from(RepositoryProfile profile) {
        return new RepositoryProfileResponse(
                profile.id().value(),
                profile.assetId().value(),
                profile.analyzedRevision(),
                profile.purpose(),
                profile.techStack(),
                profile.modules(),
                profile.capabilities(),
                profile.reusableAssets(),
                profile.limitations(),
                profile.risks(),
                profile.evidence().stream().map(EvidencePayload::from).toList());
    }
}
