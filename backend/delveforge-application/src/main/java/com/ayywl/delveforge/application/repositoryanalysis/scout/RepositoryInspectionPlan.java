package com.ayywl.delveforge.application.repositoryanalysis.scout;

import java.util.List;

/**
 * 一次 Scout 的**可信**结果：在某个 revision 上，接下来应该去读哪些文件。
 *
 * <pre>
 * analyzedRevision
 *         ↓
 * areas  →  RepositoryInspectionArea（标签 + 已解析的描述符，按顺序）
 * </pre>
 *
 * <h2>它回答什么，不回答什么</h2>
 *
 * <pre>
 * 回答     where should we inspect?      ——接下来去哪里看
 * 不回答   what does it actually prove?  ——这些内容说明了什么
 * </pre>
 *
 * <p>第二个问题是最终 Repository Analysis 的职责。本计划里的标签与分组只是**编排提示**，
 * 绝不能被当作 RepositoryProfile 的能力或依据：模型只见过路径，没见过代码。
 *
 * <h2>它不触发任何读取</h2>
 *
 * <p>本类型不持有 Workspace 能力，也没有任何读取入口。它描述的是意图，不是动作：
 * 「按计划去读文件」「基础材料与定向源码各占多少预算」「重复引用怎么合并」
 * 都属于后续阶段，当前不存在。
 *
 * <h2>生命周期</h2>
 *
 * <p>不持久化、不进入 Domain。它由一次 Scout 调用产生，随该次 Repository Analysis 结束而失效
 * ——{@code RF-*} 编号只在建立它的那张 Map 内有效，换一个 revision 即指向别的文件。
 *
 * <p>本类型不做去重或合并：同一个文件允许多次出现在不同区域，那是模型表达
 * 「这个文件同时值得从几个角度看」的方式，合并不属于本层。
 */
public final class RepositoryInspectionPlan {

    private final String analyzedRevision;

    private final List<RepositoryInspectionArea> areas;

    private RepositoryInspectionPlan(String analyzedRevision,
                                     List<RepositoryInspectionArea> areas) {
        this.analyzedRevision = analyzedRevision;
        this.areas = areas;
    }

    /**
     * 建立一份查看计划。
     *
     * @param analyzedRevision 本次分析固定的 commit id，不得为空白
     * @param areas            有序的查看区域，不得为 {@code null} 或空，元素不得为 {@code null}
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryInspectionPlan of(String analyzedRevision,
                                              List<RepositoryInspectionArea> areas) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository Inspection Plan 必须指定 analyzedRevision");
        }
        if (areas == null || areas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Repository Inspection Plan 的 areas 不能为空");
        }
        for (RepositoryInspectionArea area : areas) {
            if (area == null) {
                throw new IllegalArgumentException(
                        "Repository Inspection Plan 的 areas 不能包含 null");
            }
        }
        return new RepositoryInspectionPlan(analyzedRevision, List.copyOf(areas));
    }

    /**
     * 本次计划对应的已解析 revision。
     *
     * <p>它与计划里的描述符共同构成可追溯的引用：描述符里的路径只有配上这个 revision
     * 才指认一份确定的内容。
     */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 有序的查看区域，顺序与模型给出的一致（较早的是它建议先看的）。 */
    public List<RepositoryInspectionArea> areas() {
        return areas;
    }

    public int areaCount() {
        return areas.size();
    }
}
