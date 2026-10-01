package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import java.util.List;
import java.util.Optional;

/**
 * 一次 Scout 调用的输入：某个 {@code analyzedRevision} 上全部**源码候选**的描述符。
 *
 * <pre>
 * RepositoryMap
 *         ↓  取 SCOUT_SOURCE 这一组
 * RepositoryScoutInputs（analyzedRevision + 描述符清单）
 * </pre>
 *
 * <h2>它只有描述符，没有文件内容</h2>
 *
 * <p>与 {@link RepositoryMap} 一样，本类型不持有任何文件内容，也不提供读取入口。
 * Scout 能看到的只有路径、大小、语言、材料类别与结构角色提示——这正是「只看一眼整棵树」
 * 之所以有界的原因（ADR-0004）。一旦把内容带进来，这个阶段就退化成另一次有界采样。
 *
 * <h2>不采样、不截断</h2>
 *
 * <p>当前把该 Map 的**全部**源码候选都放进清单。判断哪些值得读是 Scout 的职责，
 * 在把清单交给它之前先按预算裁掉一部分，等于替它做了决定，也就把 M1 的问题原样带回来。
 * 分层 Scout、独立预算与聚焦轮转都属于后续阶段。
 *
 * <h2>顺序</h2>
 *
 * <p>清单顺序就是 {@link RepositoryMap#entries()} 的顺序（相对路径升序）。
 * 它是确定的，因此同一个 revision 上的同一次调用会得到同一份清单；
 * 但清单顺序**不是**优先级——优先级由 Scout 在 {@code fileRefs} 里表达。
 *
 * <p>本类型是一次调用内的中间结构：不持久化、不进入 Domain，换一个 revision 即失效。
 */
public final class RepositoryScoutInputs {

    private final RepositoryMap map;

    private final List<RepositoryMapEntry> catalog;

    private RepositoryScoutInputs(RepositoryMap map, List<RepositoryMapEntry> catalog) {
        this.map = map;
        this.catalog = catalog;
    }

    /**
     * 从一张 Repository Map 建立本次 Scout 调用的输入。
     *
     * <p>没有任何源码候选时直接拒绝：那种调用只可能失败或让模型凭空编造，
     * 不该先付一次模型调用的代价（与 M1「没有可分析材料时不问模型」同一条取舍）。
     *
     * @param map 本次分析建立的 Map，不得为 {@code null}
     * @return 该 Map 的源码候选清单
     * @throws IllegalArgumentException map 为 {@code null}，或它没有任何源码候选
     */
    public static RepositoryScoutInputs of(RepositoryMap map) {
        if (map == null) {
            throw new IllegalArgumentException("RepositoryScoutInputs 必须指定 map");
        }
        List<RepositoryMapEntry> catalog = map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE);
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException(
                    "本次 Repository Map 没有任何源码候选，Scout 调用没有可选的输入: "
                            + map.analyzedRevision());
        }
        return new RepositoryScoutInputs(map, catalog);
    }

    /** 本次调用固定的 commit id。 */
    public String analyzedRevision() {
        return map.analyzedRevision();
    }

    /** 源码候选描述符，顺序与 Map 自身一致（相对路径升序）。 */
    public List<RepositoryMapEntry> catalog() {
        return catalog;
    }

    public int size() {
        return catalog.size();
    }

    /**
     * 按引用取出本次调用所依据的那张 Map 中的描述符，**不限于**源码候选。
     *
     * <p>它是引用校验的唯一依据：模型返回的 {@code RF-*} 必须拿建立本次输入的那**同一张**
     * Map 解析，而不是重新查一次磁盘或换一张 Map。
     *
     * <p>之所以不限于源码候选，是因为校验需要把两种情况区分开：
     *
     * <pre>
     * 编号在 Map 里根本不存在        → 模型编造了引用
     * 编号存在，但不是源码候选      → 模型越过了本次给它的范围
     * </pre>
     *
     * <p>只暴露这一个查询入口而不是整张 Map：校验需要的能力就是「按编号找描述符」，
     * 给出更多只会让调用方有机会去读它本不该读的那几组。业务代码取候选请用
     * {@link #catalog()}。
     *
     * @param reference 待解析的引用；{@code null} 视为不存在
     * @return 对应的描述符；不属于本次 Map 时为空
     */
    public Optional<RepositoryMapEntry> findInMap(RepositoryFileReference reference) {
        return map.find(reference);
    }
}
