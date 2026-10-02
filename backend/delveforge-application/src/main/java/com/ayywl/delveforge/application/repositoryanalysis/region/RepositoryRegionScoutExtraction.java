package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Region Scout 调用：把一棵 Region 视图的某一层交给模型，换回「接下来探索哪几个区域」。
 *
 * <pre>
 * RepositoryRegionCatalog（某一层的兄弟 Region + RR-* 编号）
 *         ↓  Prompt（只有目录描述符，没有文件与内容）
 * Region Scout（AI Gateway）
 *         ↓  严格解析 → 引用校验 → 换回真实 Region
 * RepositoryRegionSelection（有序的分支优先级）
 * </pre>
 *
 * <p>它复用既有的 AI Gateway 抽象与严格解析口径（RULE-ARCH-008、RULE-DOM-003），
 * 与 {@code RepositoryScoutExtraction} 是同一形状、不同粒度的一层。
 *
 * <h2>它问的是「去哪里」</h2>
 *
 * <p>提示词里写死了这个边界：模型看不到代码，因此没有资格对仓库下结论。它只能依据目录前缀、
 * 规模与语言/结构提示指出「接下来应该往哪几个目录里看」。产出是**导航提示**，不是仓库事实
 * （ADR-0005）。
 *
 * <h2>失败不留下任何可信结果</h2>
 *
 * <p>Gateway 失败、解析失败、引用校验失败——任一种都以异常结束，不会返回部分结果。
 * 也不存在「丢掉不合法的引用、保留其余」这种降级。
 *
 * <h2>它不判定目录上限</h2>
 *
 * <p>与 File Scout 一致：本类提供 {@link #catalogPayloadBytes} 供调用方「先量再调」，
 * 但**不自己判定上限**。是否超出属于调用的那一层的判断（{@link RegionNavigationLimits}）。
 */
public final class RepositoryRegionScoutExtraction {

    /**
     * 系统指令。
     *
     * <p>要求模型只输出 json；区域数量上限来自配置，因此在构造时格式化进来，
     * 避免提示词与解析器各写一份会彼此漂移的范围。
     */
    private static String systemInstruction(int maxSelectedRegions) {
        return """
            你是 DelveForge 的 Repository 区域侦察组件。

            你的任务不是分析代码，也不是给出任何结论。你拿到一份**区域清单**——每个区域是一个
            目录前缀，以及它下面的源码文件数量、子目录数量、出现过的语言与结构角色提示，
            但**没有文件内容**。

            你要回答的只有一个问题：

                为了理解这个项目真正实现过什么业务，接下来应该往哪几个目录里继续看？

            只输出一个 json 对象，不要输出解释、Markdown 代码块或任何其他文字。格式如下：

            {
              "regionRefs": ["RR-1", "RR-5"]
            }

            规则：
            - 给出 %d 到 %d 个区域。
            - regionRefs 按建议的探索顺序排列：越靠前越应该先看。
            - regionRefs 只能使用清单里给出的编号。不要创造编号，也不要给出目录路径。
            - 不要重复同一个编号。

            边界：
            - 你不是在给这个仓库下结论。选择只是「打算去哪里看」的提示，
              不是已经确认的仓库能力、可复用资产或风险。
            - 不要输出能力列表、复用资产、风险、复杂度、置信度或任何分析结论。
            - 你看到的只是目录规模与语言提示，看不到代码，因此不要假装读过它们。
            """.formatted(
                    AiRegionSelectionProposal.MIN_SELECTED_REGIONS,
                    maxSelectedRegions);
    }

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final String systemInstruction;
    private final RepositoryRegionProposalParser proposalParser;
    private final RepositoryRegionProposalResolver proposalResolver;

    /**
     * @param aiGateway          AI 能力，不得为 {@code null}
     * @param objectMapper       读取 json 的映射器，不得为 {@code null}
     * @param maxSelectedRegions 一次 Region Scout 最多可选多少个区域，必须大于 0
     */
    public RepositoryRegionScoutExtraction(AiGateway aiGateway,
                                           ObjectMapper objectMapper,
                                           int maxSelectedRegions) {
        if (aiGateway == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 objectMapper");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.systemInstruction = systemInstruction(maxSelectedRegions);
        this.proposalParser =
                new RepositoryRegionProposalParser(objectMapper, maxSelectedRegions);
        this.proposalResolver = new RepositoryRegionProposalResolver();
    }

    /**
     * 依据本次 Region 目录提取「接下来探索哪些区域」。
     *
     * <p>AI 调用、解析与引用校验都发生在同一步内，任一失败都以异常结束，不返回半成品。
     *
     * @param catalog 本次导航的 Region 目录，不得为 {@code null}
     * @return 模型提出、并已通过结构与引用校验的区域选择，顺序即分支优先级
     * @throws IllegalArgumentException  catalog 为 {@code null}
     * @throws com.ayywl.delveforge.application.port.ai.AiGatewayException
     *                                   AI 调用失败、返回内容不满足约定，或引用了本次没有提供的编号
     */
    public RepositoryRegionSelection scout(RepositoryRegionCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 catalog");
        }
        AiRegionSelectionProposal proposal = proposalParser.parse(
                aiGateway.generate(buildRequest(catalog)));
        return proposalResolver.resolve(proposal, catalog);
    }

    /**
     * 本次 Region Scout 会发给模型的**目录载荷**大小，按 UTF-8 字节计。
     *
     * <p>与 File Scout 同一个口径：量的是用户消息——也就是区域清单那一段；系统指令是固定文本，
     * 不随仓库变化。存在的原因是「先量再调」：模型调用不便宜，而清单过大是本版本处理不了的形状。
     *
     * @param catalog 本次导航的 Region 目录，不得为 {@code null}
     * @return 目录载荷的 UTF-8 字节数
     * @throws IllegalArgumentException catalog 为 {@code null}
     */
    public int catalogPayloadBytes(RepositoryRegionCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 catalog");
        }
        return describeCatalog(catalog).getBytes(StandardCharsets.UTF_8).length;
    }

    private AiRequest buildRequest(RepositoryRegionCatalog catalog) {
        return new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, systemInstruction),
                        new AiMessage(AiRole.USER, describeCatalog(catalog))),
                AiResponseFormat.JSON);
    }

    /**
     * 请求内容：本次导航固定的 revision，加上全部区域描述符。
     *
     * <p>只发描述符，不发文件内容，也不发文件清单——这是「按目录分层」成立的前提。
     */
    private String describeCatalog(RepositoryRegionCatalog catalog) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analyzedRevision", catalog.analyzedRevision());

        List<Map<String, Object>> regions = new ArrayList<>(catalog.size());
        for (RepositoryRegionCatalog.RegionEntry entry : catalog.entries()) {
            RepositoryRegion region = entry.region();
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("reference", entry.reference().value());
            described.put("pathPrefix", region.pathPrefix());
            described.put("directSourceFiles", region.directSourceFileCount());
            described.put("descendantSourceFiles", region.descendantSourceFileCount());
            described.put("childRegionsWithSource", region.childRegionCount());
            described.put("languages", region.languages().stream().map(Enum::name).toList());
            described.put("roleHints", region.roleHints().stream().map(Enum::name).toList());
            regions.add(described);
        }
        payload.put("regionCatalog", regions);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Region 目录载荷", exception);
        }
    }
}
