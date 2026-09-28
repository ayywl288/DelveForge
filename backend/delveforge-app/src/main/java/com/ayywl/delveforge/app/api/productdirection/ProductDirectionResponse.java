package com.ayywl.delveforge.app.api.productdirection;

import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import java.util.List;

/**
 * Product Direction 在 HTTP 接口上的表示。
 *
 * <p>它承载用户查看一条候选方向所需的全部领域信息：方向内容、它依据的 User Profile 版本、
 * 它实际依据的 Repository Profile、候选资产、生命周期状态，以及关键判断与依据的对应关系。
 *
 * <pre>
 * userProfileId + userProfileRevision  这条方向是对着哪一版用户画像推荐的（INV-D01、INV-D08）
 * repositoryProfileIds                 它实际依据了哪几份分析（INV-D05）
 * candidateAssetIds                    它认为可以用哪些已有软件资产（INV-D10）
 * evidenceSupport                      它凭什么这么说（INV-D06）
 * </pre>
 *
 * <p>这些字段全部来自已经保存的领域事实，接口层不做任何再判断：不重新解释
 * {@code repositoryProfileIds}，也不替调用方推导 {@code status} 的含义。
 *
 * <h2>不暴露 AI 通信协议</h2>
 *
 * <p>响应里不会出现 {@code U-E1} / {@code R2-E3} 这类引用。它们只是一次 AI 调用内的临时
 * 编号，在 Application 侧就已经被换成了真实依据与它们的出处（{@code EvidenceBasisPayload}
 * 的 {@code evidence} 与 {@code origin}），因此调用方看到的是可独立理解的追溯信息。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param id                  方向标识
 * @param userProfileId       生成该方向所依据的 User Profile
 * @param userProfileRevision 生成该方向所依据的 User Profile 版本
 * @param repositoryProfileIds 该方向实际依据的 Repository Profile
 * @param title               方向的简短名称
 * @param problem             希望解决的核心问题或需求
 * @param targetProduct       候选产品的大致目标形态
 * @param userFit             与 User Profile 的主要匹配点
 * @param candidateAssetIds   可以用于实现该方向的 Software Asset
 * @param differentiation     与原项目或常见方案相比的主要差异
 * @param technicalValue      可以体现或获得的技术价值
 * @param estimatedComplexity 对整体演化成本的粗粒度判断
 * @param risks               当前已知主要风险
 * @param status              生命周期状态；新发现的候选方向一律为 {@code CANDIDATE}
 * @param evidenceSupport     关键推荐判断与依据的对应关系
 */
public record ProductDirectionResponse(
        String id,
        String userProfileId,
        int userProfileRevision,
        List<String> repositoryProfileIds,
        String title,
        String problem,
        String targetProduct,
        String userFit,
        List<String> candidateAssetIds,
        String differentiation,
        String technicalValue,
        String estimatedComplexity,
        List<String> risks,
        ProductDirectionStatus status,
        ProductDirectionEvidenceSupportResponse evidenceSupport) {

    /**
     * 把领域方向映射为它的接口表示。
     *
     * <p>映射放在资源自己的类上：发现端点与读取端点返回同一个资源，
     * 放在任一 Controller 里都会让另一处反过来依赖它（或复制一份）。
     *
     * @param direction 领域侧方向，不得为 {@code null}
     */
    public static ProductDirectionResponse from(ProductDirection direction) {
        return new ProductDirectionResponse(
                direction.id().value(),
                direction.userProfileId().value(),
                direction.userProfileRevision(),
                direction.repositoryProfileIds().stream()
                        .map(repositoryProfileId -> repositoryProfileId.value())
                        .toList(),
                direction.title(),
                direction.problem(),
                direction.targetProduct(),
                direction.userFit(),
                direction.candidateAssetIds().stream()
                        .map(assetId -> assetId.value())
                        .toList(),
                direction.differentiation(),
                direction.technicalValue(),
                direction.estimatedComplexity(),
                direction.risks(),
                direction.status(),
                ProductDirectionEvidenceSupportResponse.from(direction.evidenceSupport()));
    }
}
