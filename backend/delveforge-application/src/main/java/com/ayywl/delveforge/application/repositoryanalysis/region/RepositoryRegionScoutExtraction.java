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
 * <h2>字节守卫在调用边界内执行</h2>
 *
 * <p>与 File Scout 不同的一点：本类**自己**在调用模型之前执行 {@link RegionNavigationLimits}
 * 的字节守卫——先序列化一次，量这一份，超限即失败，否则原样发出**同一份**载荷。
 *
 * <p>理由是 ADR-0005 把「Region Catalog 自身超出预算」列为必须失败关闭的情形之一。
 * 如果守卫只存在于编排层，「调用者记得先量一下」就成了唯一的保证——而那不是保证，
 * 绕过去就会真的付出一次模型调用。把上限收进调用边界之后，超限的目录**不可能**产生模型调用。
 *
 * <p>{@link #catalogPayloadBytes} 仍然保留，供编排层在调模型之前自行判断要不要继续下钻；
 * 它与调用走同一个渲染入口，因此量到的就是即将发出的那一份。
 */
public final class RepositoryRegionScoutExtraction {

    /**
     * 系统指令。
     *
     * <p>要求模型只输出 json。区域数量上限来自配置，示例引用取自**本次**的目录，
     * 两者都在这里格式化进来——避免提示词与解析器各写一份会彼此漂移的范围或格式。
     *
     * <p>示例必须是本次目录里真实存在的编号（含作用域）。写一个 {@code RR-1} 这样的
     * 裸编号会让模型照抄出一个解析器必然拒绝的答案。
     */
    private String systemInstruction(RepositoryRegionCatalog catalog) {
        String example = catalog.entries().get(0).reference().value();
        return """
            你是 DelveForge 的 Repository 区域侦察组件。

            你的任务不是分析代码，也不是给出任何结论。你拿到一份**区域清单**——每个区域是一个
            目录前缀，以及它下面的源码文件数量、子目录数量、出现过的语言与结构角色提示，
            但**没有文件内容**。

            你要回答的只有一个问题：

                为了理解这个项目真正实现过什么业务，接下来应该往哪几个目录里继续看？

            只输出一个 json 对象，不要输出解释、Markdown 代码块或任何其他文字。格式如下：

            {
              "regionRefs": ["%s"]
            }

            规则：
            - 给出 %d 到 %d 个区域。
            - regionRefs 按建议的探索顺序排列：越靠前越应该先看。
            - regionRefs 必须从清单里**原样复制完整编号**（例如上面示例那种形式，
              包含前面那一段标识）。不要简写、不要只写序号、不要创造编号，也不要给出目录路径。
            - 不要重复同一个编号。

            边界：
            - 你不是在给这个仓库下结论。选择只是「打算去哪里看」的提示，
              不是已经确认的仓库能力、可复用资产或风险。
            - 不要输出能力列表、复用资产、风险、复杂度、置信度或任何分析结论。
            - 你看到的只是目录规模与语言提示，看不到代码，因此不要假装读过它们。
            """.formatted(
                    example,
                    AiRegionSelectionProposal.MIN_SELECTED_REGIONS,
                    maxSelectedRegions);
    }

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final RegionNavigationLimits limits;
    private final int maxSelectedRegions;
    private final RepositoryRegionProposalParser proposalParser;
    private final RepositoryRegionProposalResolver proposalResolver;

    /**
     * @param aiGateway    AI 能力，不得为 {@code null}
     * @param objectMapper 读取 json 的映射器，不得为 {@code null}
     * @param limits       本次导航的守卫上限（字节上限 + 选择数量上限），不得为 {@code null}
     */
    public RepositoryRegionScoutExtraction(AiGateway aiGateway,
                                           ObjectMapper objectMapper,
                                           RegionNavigationLimits limits) {
        if (aiGateway == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 objectMapper");
        }
        if (limits == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 limits");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.limits = limits;
        this.maxSelectedRegions = limits.maxSelectedRegions();
        this.proposalParser = new RepositoryRegionProposalParser(
                objectMapper, limits.maxSelectedRegions());
        this.proposalResolver = new RepositoryRegionProposalResolver();
    }

    /**
     * 依据本次 Region 目录提取「接下来探索哪些区域」。
     *
     * <p>顺序是：序列化一次 → 用**这一份**载荷执行字节守卫 → 原样发出它 → 解析 → 引用校验。
     * 守卫在 Gateway 之前，因此超限的目录不会产生任何模型调用；解析与引用校验失败也都以异常结束，
     * 不返回半成品。
     *
     * @param catalog 本次导航的 Region 目录，不得为 {@code null}
     * @return 模型提出、并已通过结构与引用校验的区域选择，顺序即分支优先级
     * @throws IllegalArgumentException                   catalog 为 {@code null}
     * @throws RepositoryRegionCatalogTooLargeException   目录载荷超过本次导航的字节上限
     * @throws com.ayywl.delveforge.application.port.ai.AiGatewayException
     *                                   AI 调用失败、返回内容不满足约定，或引用了本次没有提供的编号
     */
    public RepositoryRegionSelection scout(RepositoryRegionCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionScoutExtraction 必须指定 catalog");
        }
        String payload = describeCatalog(catalog);
        limits.requireCatalogWithinLimit(
                payload.getBytes(StandardCharsets.UTF_8).length, catalog.analyzedRevision());

        AiRegionSelectionProposal proposal = proposalParser.parse(
                aiGateway.generate(buildRequest(catalog, payload)));
        return proposalResolver.resolve(proposal, catalog);
    }

    /**
     * 本次 Region Scout 会发给模型的**目录载荷**大小，按 UTF-8 字节计。
     *
     * <p>量的是**用户消息**——也就是区域清单那一段，与 File Scout 同一个口径。
     *
     * <p>系统指令不在这里度量：它含一个取自本次目录的示例引用，长度有界（一个编号），
     * 不随仓库规模变化；随规模增长的是用户消息里的清单，而守卫守的正是它。
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

    private AiRequest buildRequest(RepositoryRegionCatalog catalog, String payload) {
        return new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, systemInstruction(catalog)),
                        new AiMessage(AiRole.USER, payload)),
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
