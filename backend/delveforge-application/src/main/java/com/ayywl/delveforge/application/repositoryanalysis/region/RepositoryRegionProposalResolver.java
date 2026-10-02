package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 把不可信的 {@link AiRegionSelectionProposal} 解析为可信的 {@link RepositoryRegionSelection}。
 *
 * <pre>
 * AiRegionSelectionProposal
 *         +
 * RepositoryRegionCatalog（建立本次调用时提供的那一份）
 *         ↓  逐个引用校验 → 换回真实 Region
 * RepositoryRegionSelection
 * </pre>
 *
 * <h2>这是引用离开 AI 边界的唯一出口</h2>
 *
 * <p>{@code RR-*} 是本次调用内的临时短名，模型只能**指认**。本类把每个编号换回它本来对应的
 * {@link RepositoryRegion}——换回之后，后续阶段拿到的是服务端自己的区域信息，而不是模型给的。
 * 模型输出里的路径永远不进入后续调用。
 *
 * <h2>校验规则</h2>
 *
 * <pre>
 * 编号不在本次 Catalog 中            → 拒绝（模型编造了引用）
 * 同一编号在列表里重复               → 拒绝（解析层与解析器各拦一次）
 * </pre>
 *
 * <p>「来自另一次调用的引用」在语义上就等价于「不在本次 Catalog 中」：引用按位置编号，
 * 只可能是建立本次调用时那份目录里的第 n 个区域（见 {@link RepositoryRegionReference}）。
 * 因此这里用同一个判定覆盖两种情形，不做区分。
 *
 * <h2>为什么引用问题抛 AiGatewayException</h2>
 *
 * <p>与 {@code RepositoryScoutProposalResolver} 一致：「模型引用了一个本次没有提供的编号」
 * 与「模型返回的 json 结构不对」是同一类失败——都不是调用方写错了请求，而是模型没有遵守约定。
 * 因此与解析层共用同一个异常类型，让 Interface 层把它们映射成同一类协议错误（502）。
 *
 * <p>{@code null} 参数仍抛 {@link IllegalArgumentException}：那是调用方传错了东西。
 *
 * <h2>失败是一次的，不是逐条的</h2>
 *
 * <p>任何一个引用不合法都让整次选择失败，不会丢掉那个引用、保留其余部分继续。
 * 校验全部完成之后才构造结果，因此不存在构造了一半的选择。
 */
public final class RepositoryRegionProposalResolver {

    /**
     * 校验提议并把每个引用换回真实 Region。
     *
     * @param proposal 解析后的区域选择提议，不得为 {@code null}
     * @param catalog  建立本次调用时提供的 Region 目录，不得为 {@code null}
     * @return 由已校验区域构成的可信选择，顺序与模型给出的一致
     * @throws IllegalArgumentException 任一参数为 {@code null}
     * @throws AiGatewayException       引用了本次没有提供的编号，或引用了重复编号
     */
    public RepositoryRegionSelection resolve(AiRegionSelectionProposal proposal,
                                             RepositoryRegionCatalog catalog) {
        if (proposal == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionProposalResolver 必须指定 proposal");
        }
        if (catalog == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionProposalResolver 必须指定 catalog");
        }

        List<RepositoryRegion> regions = new ArrayList<>(proposal.regionRefs().size());
        Set<RepositoryRegionReference> seen = new LinkedHashSet<>();
        for (RepositoryRegionReference reference : proposal.regionRefs()) {
            if (!seen.add(reference)) {
                throw new AiGatewayException(
                        "Region Scout 重复引用了 " + reference.value());
            }
            Optional<RepositoryRegion> resolved = catalog.find(reference);
            if (resolved.isEmpty()) {
                throw new AiGatewayException(
                        "Region Scout 引用了本次没有提供的编号: " + reference.value()
                                + "（该编号不属于建立本次调用时的 Region Catalog）");
            }
            regions.add(resolved.get());
        }
        return RepositoryRegionSelection.of(catalog.analyzedRevision(), regions);
    }
}
