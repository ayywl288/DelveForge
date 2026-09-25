package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import java.util.List;

/**
 * AI 提出的一条候选方向，仍停留在 AI 通信协议这一侧。
 *
 * <p>它是严格解析的直接产物：字段形状对应模型返回的 JSON 契约，{@code evidenceLinkage}
 * 里装的是本次调用中的临时引用，还没有变成真实依据。
 *
 * <p>它<b>不是</b>领域中的 {@code DirectionProposal}。两者的区别只有一处，但很关键：
 * 这里的依据是「本次提示里第几条」，领域里的依据是「哪条真实 Evidence、出自哪一份分析」。
 * 前者只有 AI 通信才有意义，后者才是可追溯的领域事实。
 *
 * <pre>
 * AiDirectionProposal   临时引用   只服务解析
 *         ↓ DirectionProposalResolver
 * DirectionProposal     真实依据   供 Domain Service 消费
 * </pre>
 *
 * <p>与领域提案一样，它不携带模型无权决定的事实：{@code ProductDirectionId}、
 * {@code userProfileId}、{@code userProfileRevision}、{@code repositoryProfileIds}、
 * {@code status}，以及 Evidence 的 {@code confidence} 与 {@code confirmed}。
 *
 * <p>不持久化，也不构成任何合法领域状态（RULE-DOM-003）。
 *
 * @param title               方向的简短名称，不得为 {@code null} 或空白
 * @param problem             希望解决的核心问题或需求，不得为 {@code null} 或空白
 * @param targetProduct       候选产品的大致目标形态，不得为 {@code null} 或空白
 * @param userFit             与 User Profile 的主要匹配点，不得为 {@code null} 或空白
 * @param candidateAssetIds   可以用于实现该方向的 Software Asset；不得为 {@code null}
 *                            或空，元素不得为 {@code null}
 * @param differentiation     与原项目或常见方案相比的主要差异，不得为 {@code null} 或空白
 * @param technicalValue      可以体现或获得的技术价值，不得为 {@code null} 或空白
 * @param estimatedComplexity 对整体演化成本的粗粒度判断，不得为 {@code null} 或空白
 * @param risks               当前已知主要风险；不得为 {@code null}，可以为空
 * @param evidenceLinkage     关键判断与临时依据引用的对应关系，不得为 {@code null}
 */
public record AiDirectionProposal(
        String title,
        String problem,
        String targetProduct,
        String userFit,
        List<SoftwareAssetId> candidateAssetIds,
        String differentiation,
        String technicalValue,
        String estimatedComplexity,
        List<String> risks,
        AiDirectionEvidenceLinkage evidenceLinkage) {

    public AiDirectionProposal {
        title = requireText(title, "title");
        problem = requireText(problem, "problem");
        targetProduct = requireText(targetProduct, "targetProduct");
        userFit = requireText(userFit, "userFit");
        differentiation = requireText(differentiation, "differentiation");
        technicalValue = requireText(technicalValue, "technicalValue");
        estimatedComplexity = requireText(estimatedComplexity, "estimatedComplexity");

        candidateAssetIds = copyAssets(candidateAssetIds);
        if (candidateAssetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "AI Direction Proposal 必须至少标识一个 Candidate Software Asset");
        }

        risks = copyRisks(risks);

        if (evidenceLinkage == null) {
            throw new IllegalArgumentException(
                    "AI Direction Proposal 必须给出 evidenceLinkage");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI Direction Proposal 必须给出 " + field);
        }
        return value;
    }

    private static List<SoftwareAssetId> copyAssets(List<SoftwareAssetId> assetIds) {
        if (assetIds == null) {
            throw new IllegalArgumentException(
                    "AI Direction Proposal 的 candidateAssetIds 不能为 null");
        }
        for (SoftwareAssetId assetId : assetIds) {
            if (assetId == null) {
                throw new IllegalArgumentException(
                        "AI Direction Proposal 的 candidateAssetIds 不能包含 null");
            }
        }
        return List.copyOf(assetIds);
    }

    private static List<String> copyRisks(List<String> risks) {
        if (risks == null) {
            throw new IllegalArgumentException("AI Direction Proposal 的 risks 不能为 null");
        }
        for (String risk : risks) {
            if (risk == null || risk.isBlank()) {
                throw new IllegalArgumentException(
                        "AI Direction Proposal 的 risks 不能包含空值");
            }
        }
        return List.copyOf(risks);
    }
}
