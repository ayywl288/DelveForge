package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryAnalysisExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryAnalysisProposal;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryEvidenceProposal;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分析一个 Software Asset 的 Repository，形成并保存一份 Repository Profile。
 *
 * <p>对应 DOMAIN_MODEL.md §8.3 的 Analyze Repository。它把此前各自独立的能力
 * 串成一条链路：
 *
 * <pre>
 * SoftwareAsset
 *         ↓
 * 只读 Workspace
 *         ↓
 * 解析一次 analyzedRevision
 *         ↓
 * 材料（listEntries → readFile @ 该 revision）
 *         ↓
 * AI 提议
 *         ↓
 * RepositoryProfile
 *         ↓
 * 保存
 * </pre>
 *
 * <h2>Revision 一致性</h2>
 *
 * <p>HEAD 只解析一次，之后所有结构读取与文件读取都带着这个具体的 commit id，
 * 不再重新解析 HEAD。因此 RepositoryProfile.analyzedRevision 与它所描述的内容
 * 严格对应：即使分析期间源 Repository 又产生了新的提交，本次分析也不会混入
 * 另一个 revision 的内容。
 *
 * <h2>失败不留下任何东西</h2>
 *
 * <p>保存是整条链路的最后一步，且只发生一次。前置步骤——资产校验、Workspace 可读性、
 * revision 解析、材料读取、AI 提取、领域创建——任一失败都以异常结束，此时
 * RepositoryProfile 尚未写入，不会留下半成品快照。
 *
 * <h2>只读能力边界</h2>
 *
 * <p>本类只依赖 {@link WorkspaceReadPort}：它没有 {@code WorkspaceMutationPort} 的引用，
 * 也不直接执行 Git / Shell / 文件系统操作（RULE-ARCH-009、RULE-ARCH-010）。
 * Repository Analysis 对原始软件资产保持只读（RULE-DOM-005）。
 *
 * <h2>不做的判断</h2>
 *
 * <pre>
 * 不改写 location          领域模型把位置的表现形式交给资产类型决定（§3.2），
 *                          这里只把它映射为 WorkspaceRef
 * 不判断资产类型是否受支持  MVP 中 SoftwareAssetType 只有 GIT_REPOSITORY 一个取值，
 *                          因此 §8.3 的「类型必须是当前支持的类型」当前不可能失败；
 *                          出现第二种类型时再在这里校验
 * 不判断分析结论是否正确    提议由 AI 提出，是否被接受由 RepositoryProfile 判定
 * </pre>
 */
public class AnalyzeRepositoryUseCase {

    private final SoftwareAssetRepository softwareAssetRepository;
    private final WorkspaceReadPort workspace;
    private final RepositoryAnalysisMaterialCollector materialCollector;
    private final RepositoryAnalysisExtraction analysisExtraction;
    private final RepositoryProfileRepository repositoryProfileRepository;

    public AnalyzeRepositoryUseCase(
            SoftwareAssetRepository softwareAssetRepository,
            WorkspaceReadPort workspace,
            RepositoryAnalysisMaterialCollector materialCollector,
            RepositoryAnalysisExtraction analysisExtraction,
            RepositoryProfileRepository repositoryProfileRepository) {

        if (softwareAssetRepository == null) {
            throw new IllegalArgumentException("AnalyzeRepositoryUseCase 必须指定 softwareAssetRepository");
        }
        if (workspace == null) {
            throw new IllegalArgumentException("AnalyzeRepositoryUseCase 必须指定 workspace");
        }
        if (materialCollector == null) {
            throw new IllegalArgumentException("AnalyzeRepositoryUseCase 必须指定 materialCollector");
        }
        if (analysisExtraction == null) {
            throw new IllegalArgumentException("AnalyzeRepositoryUseCase 必须指定 analysisExtraction");
        }
        if (repositoryProfileRepository == null) {
            throw new IllegalArgumentException(
                    "AnalyzeRepositoryUseCase 必须指定 repositoryProfileRepository");
        }

        this.softwareAssetRepository = softwareAssetRepository;
        this.workspace = workspace;
        this.materialCollector = materialCollector;
        this.analysisExtraction = analysisExtraction;
        this.repositoryProfileRepository = repositoryProfileRepository;
    }

    /**
     * 分析指定资产并保存形成的新快照。
     *
     * @param softwareAssetId 待分析的 Software Asset
     * @return 已保存的 Repository Profile
     * @throws SoftwareAssetNotFoundException     该资产不存在
     * @throws com.ayywl.delveforge.domain.asset.SoftwareAssetNotReadableException
     *                                            该资产当前不允许读取（INV-A01）
     * @throws WorkspaceException                 该资产的位置当前不是可读取的本地 Git Repository，
     *                                            或 Workspace 读取失败
     * @throws com.ayywl.delveforge.application.port.ai.AiGatewayException
     *                                            AI 调用失败，或返回内容不满足约定
     * @throws IllegalArgumentException          领域拒绝这次分析（例如分析不出一条可用结论）
     */
    public RepositoryProfile analyze(SoftwareAssetId softwareAssetId) {
        SoftwareAsset asset = softwareAssetRepository.findById(softwareAssetId)
                .orElseThrow(() -> new SoftwareAssetNotFoundException(softwareAssetId));

        // 资产侧的读取权限是分析的前提，由 Aggregate 判定（INV-A01）
        asset.requireAnalysisAllowed();

        WorkspaceRef workspaceRef = new WorkspaceRef(asset.location());
        if (!workspace.isReadableRepository(workspaceRef)) {
            throw new RepositoryNotAnalyzableException(
                    "该 Software Asset 的位置当前不是可读取的本地 Git Repository: "
                            + asset.location());
        }

        // 只在这里解析一次 HEAD：之后所有读取都固定在这个 revision 上
        String analyzedRevision = workspace.headRevision(workspaceRef);

        List<RepositorySourceFile> material =
                materialCollector.collect(workspaceRef, analyzedRevision);
        RepositoryAnalysisProposal proposal = analysisExtraction.extract(material);

        RepositoryProfile profile = RepositoryProfile.create(
                new RepositoryProfileId(UUID.randomUUID().toString()),
                asset.id(),
                analyzedRevision,
                proposal.purpose(),
                proposal.techStack(),
                proposal.modules(),
                proposal.capabilities(),
                proposal.reusableAssets(),
                proposal.limitations(),
                proposal.risks(),
                toEvidence(proposal.evidence()));

        // 走到这里才写入：任何前置失败都不会留下快照
        repositoryProfileRepository.save(profile);

        return profile;
    }

    /**
     * 把模型提出的依据转成领域 Evidence。
     *
     * <p>来源固定为 {@link EvidenceSourceType#REPOSITORY}，{@code sourceRef} 是材料中的路径——
     * 在提取阶段已经核对过它确实来自本次读取的文件，因此每条 Evidence 都能指回真实的代码、
     * 配置或结构（DOMAIN_MODEL.md §3.6）。
     *
     * <p>{@code confirmed} 固定为 {@code false}：模型得出的结论属于系统推断，
     * 不等于「已被确认的事实」（§8.3 的分析结论同样需要用户判断）。
     * {@code confidence} 留空——领域模型尚未规定其数值口径，不在这里臆造一个刻度。
     * 这两点与用户探索中从用户输入提取 Evidence 的口径一致。
     */
    private static List<Evidence> toEvidence(List<RepositoryEvidenceProposal> proposed) {
        List<Evidence> evidence = new ArrayList<>(proposed.size());
        for (RepositoryEvidenceProposal item : proposed) {
            evidence.add(new Evidence(
                    EvidenceSourceType.REPOSITORY,
                    item.sourceRef(),
                    item.claim(),
                    null,
                    false));
        }
        return List.copyOf(evidence);
    }
}
