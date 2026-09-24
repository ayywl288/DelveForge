package com.ayywl.delveforge.domain.direction;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import java.util.List;

/**
 * AI 提出的一条候选 Product Direction（DOMAIN_MODEL.md §12.4、§13.1）。
 *
 * <p>它表达 AI 有资格提出的那部分推荐语义：
 *
 * <pre>
 * 这个方向解决什么问题、做成什么样、为什么适合当前用户、
 * 基于哪些已有软件资产、与常见方案有何不同、有什么技术价值、
 * 大致多少成本、有哪些风险，以及这些判断由哪些已有依据支撑
 * </pre>
 *
 * <p>它对应领域中的 {@code ProductDirection}，但在被接受之前只是 AI 边界上的中间数据：
 * 是否成立、能否构成一条合法的 Product Direction，由下一 Task 的
 * {@code ProductDirectionDiscoveryService} 与 {@code ProductDirection} Aggregate 判定
 * （RULE-DOM-003）。
 *
 * <h2>它不是 Entity</h2>
 *
 * <p>Proposal 没有 id、没有 status、没有生命周期，也不被持久化。它是一次 AI 调用的
 * 产物，不是领域事实。
 *
 * <h2>它不携带模型无权决定的事实</h2>
 *
 * <p>以下取值全部由系统决定，因此刻意不出现在本类型上：
 *
 * <pre>
 * ProductDirectionId       标识由系统生成
 * userProfileId            本次分析用的是哪个 User Profile，由调用方确定
 * userProfileRevision      同上，且必须是确定的版本（INV-D01、INV-D08）
 * repositoryProfileIds     本次分析依据了哪些 Repository Profile，由调用方确定
 * status                   新方向一律进 CANDIDATE，且选择必须来自用户（INV-D07）
 * Evidence.confidence      模型不得自行断言一条依据有多可信
 * Evidence.confirmed       同上，模型不得自行断言一条依据已被确认
 * </pre>
 *
 * <p>模型可以指出「哪条已有依据支撑了这条判断」（{@link #evidenceLinkage}），
 * 但那条依据本身的内容、来源、可信程度与确认状态都不由它给出。
 *
 * <h2>校验到哪一层</h2>
 *
 * <p>本类型只校验「给出的值是不是一个真实的值」：必填文本不得空白、集合不得为
 * {@code null}、候选资产至少一个。它不判断内容的质量——一个方向是否足够有个人相关性、
 * 依据是否足以支撑判断、几个方向之间是否足够不同，这些都不是这个类型能回答的。
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
 * @param evidenceLinkage     关键推荐判断与已有 Evidence 的对应关系，不得为 {@code null}
 */
public record DirectionProposal(
        String title,
        String problem,
        String targetProduct,
        String userFit,
        List<SoftwareAssetId> candidateAssetIds,
        String differentiation,
        String technicalValue,
        String estimatedComplexity,
        List<String> risks,
        DirectionEvidenceLinkage evidenceLinkage) {

    public DirectionProposal {
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
                    "Direction Proposal 必须至少标识一个 Candidate Software Asset");
        }

        risks = copyRisks(risks);

        if (evidenceLinkage == null) {
            throw new IllegalArgumentException(
                    "Direction Proposal 必须给出 evidenceLinkage");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Direction Proposal 必须给出 " + field);
        }
        return value;
    }

    private static List<SoftwareAssetId> copyAssets(List<SoftwareAssetId> assetIds) {
        if (assetIds == null) {
            throw new IllegalArgumentException(
                    "Direction Proposal 的 candidateAssetIds 不能为 null");
        }
        for (SoftwareAssetId assetId : assetIds) {
            if (assetId == null) {
                throw new IllegalArgumentException(
                        "Direction Proposal 的 candidateAssetIds 不能包含 null");
            }
        }
        return List.copyOf(assetIds);
    }

    private static List<String> copyRisks(List<String> risks) {
        if (risks == null) {
            throw new IllegalArgumentException("Direction Proposal 的 risks 不能为 null");
        }
        for (String risk : risks) {
            if (risk == null || risk.isBlank()) {
                throw new IllegalArgumentException(
                        "Direction Proposal 的 risks 不能包含空值");
            }
        }
        return List.copyOf(risks);
    }
}
