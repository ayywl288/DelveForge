package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 把不可信的 {@link AiRepositoryScoutProposal} 解析为可信的 {@link RepositoryInspectionPlan}。
 *
 * <pre>
 * AiRepositoryScoutProposal
 *         +
 * RepositoryScoutInputs（即建立本次调用的那张 Map）
 *         ↓  逐个引用校验 → 换回真实描述符
 * RepositoryInspectionPlan
 * </pre>
 *
 * <h2>这是引用离开 AI 边界的唯一出口</h2>
 *
 * <p>{@code RF-*} 是本次调用内的临时短名，模型只能**指认**，不能给出路径。
 * 本类把每个编号换回它本来对应的 {@link RepositoryMapEntry}——换回之后，
 * 后续阶段拿到的路径来自 Map，而不是来自模型。
 *
 * <p>因此一条硬约束：**模型输出中的路径永远不进入将来的读取调用**。
 * 模型能表达「读第 7 个」，「第 7 个是哪个文件」由服务端决定。
 *
 * <h2>校验规则</h2>
 *
 * <pre>
 * 编号在本次 Map 中不存在           → 拒绝（模型编造了引用）
 * 编号存在但不是源码候选            → 拒绝（模型越过了本次给它的范围）
 * 同一区域内重复引用                → 拒绝（解析层与解析器各拦一次）
 * 跨区域重复引用                    → 允许（同一个文件可以从几个角度看）
 * </pre>
 *
 * <h2>为什么引用问题抛 AiGatewayException</h2>
 *
 * <p>「模型引用了一个不存在、或本次没有提供的编号」与「模型返回的 json 结构不对」
 * 是同一类失败：都不是调用方写错了请求，而是模型没有遵守本次的约定。因此这里与解析层
 * 使用同一个异常类型，让 Interface 层把它们映射成同一类协议错误（502），
 * 而不是借 {@link IllegalArgumentException} 把责任推给调用方（那会变成 400）。
 *
 * <p>{@code null} 参数仍然抛 {@link IllegalArgumentException}：那是调用方传错了东西，
 * 与模型输出无关。
 *
 * <h2>失败是一次的，不是逐条的</h2>
 *
 * <p>任何一个引用不合法都让整次侦察失败，不会丢掉那个区域、保留其余部分继续。
 * 部分成功会让调用方以为「模型只指出了这几处」，而真实情况是它的输出不可用。
 * 校验全部完成之后才构造计划，因此不存在构造了一半的结果。
 */
public final class RepositoryScoutProposalResolver {

    /**
     * 校验提议并把每个引用换回真实描述符。
     *
     * @param proposal 解析后的侦察提议，不得为 {@code null}
     * @param inputs   建立本次调用时的输入，不得为 {@code null}
     * @return 由已校验的描述符构成的可信计划
     * @throws IllegalArgumentException 任一参数为 {@code null}
     * @throws AiGatewayException       引用了本次没有提供的编号，或引用了非源码候选
     */
    public RepositoryInspectionPlan resolve(AiRepositoryScoutProposal proposal,
                                            RepositoryScoutInputs inputs) {
        if (proposal == null) {
            throw new IllegalArgumentException(
                    "RepositoryScoutProposalResolver 必须指定 proposal");
        }
        if (inputs == null) {
            throw new IllegalArgumentException(
                    "RepositoryScoutProposalResolver 必须指定 inputs");
        }

        List<RepositoryInspectionArea> areas = new ArrayList<>(proposal.focusAreas().size());
        for (AiRepositoryScoutFocusArea area : proposal.focusAreas()) {
            areas.add(resolveArea(area, inputs));
        }
        return RepositoryInspectionPlan.of(inputs.analyzedRevision(), areas);
    }

    /**
     * 解析一个区域的引用，保持模型给出的顺序。
     *
     * <p>不排序、不去重、不跨区域合并：顺序是模型表达优先级的方式，
     * 而合并属于后续阶段。
     *
     * <p>区域内重复在这里**显式**拒绝，而不是留给
     * {@link RepositoryInspectionArea} 的构造校验。两者拦的是同一件事，但失败语义不同：
     * 重复引用是「模型没有认真作答」，属于 AI 边界失败；等到可信类型的构造器抛出参数异常，
     * 就变成「调用方传错了东西」，那是错误的责任归属。解析层通常已经拦过一次
     * （{@link RepositoryScoutProposalParser}），但本类不能假设提议一定来自解析层——
     * 它接受的是「不可信提议」这一类型，而不是「来自解析器的提议」。
     */
    private static RepositoryInspectionArea resolveArea(AiRepositoryScoutFocusArea area,
                                                        RepositoryScoutInputs inputs) {
        List<RepositoryMapEntry> entries = new ArrayList<>(area.fileRefs().size());
        Set<RepositoryFileReference> seen = new LinkedHashSet<>();
        for (RepositoryFileReference reference : area.fileRefs()) {
            if (!seen.add(reference)) {
                throw new AiGatewayException(
                        "Scout 在查看区域「" + area.label() + "」内重复引用了 "
                                + reference.value());
            }
            entries.add(requireScoutSourceEntry(reference, area.label(), inputs));
        }
        return new RepositoryInspectionArea(area.label(), entries);
    }

    private static RepositoryMapEntry requireScoutSourceEntry(
            RepositoryFileReference reference, String label, RepositoryScoutInputs inputs) {

        Optional<RepositoryMapEntry> resolved = inputs.findInMap(reference);
        if (resolved.isEmpty()) {
            throw new AiGatewayException(
                    "Scout 在查看区域「" + label + "」引用了本次没有提供的编号: "
                            + reference.value() + "（该编号不属于建立本次调用时的 Repository Map）");
        }

        RepositoryMapEntry entry = resolved.get();
        if (RepositoryCandidateLane.of(entry) != RepositoryCandidateLane.SCOUT_SOURCE) {
            throw new AiGatewayException(
                    "Scout 在查看区域「" + label + "」引用了本次没有作为源码候选提供的文件: "
                            + reference.value() + "（" + entry.relativePath()
                            + " 不属于本次的源码候选）");
        }
        return entry;
    }
}
