package com.ayywl.delveforge.application.repositoryanalysis.readplan;

/**
 * 一组材料选取预算：最多读几个文件、单个文件多大、总量多少。
 *
 * <h2>它是 Application 层的实现选择，不是领域规则</h2>
 *
 * <p>DOMAIN_MODEL 只要求分析针对一个确定的软件状态并形成可追溯结论，没有规定读多少、
 * 读多大。这些上限决定分析能看到什么，改变它们不需要改领域模型，但会改变分析结果的质量。
 *
 * <h2>两条通道各自持有一份，互不占用</h2>
 *
 * <p>Foundation 与定向源码各自使用独立的预算实例，任何一个都不会因为另一个用了多少而少读。
 * 这不是「把一份预算切成两半」：两条通道的材料性质完全不同——Foundation 要的是覆盖工程形态
 * 的各侧面，定向源码要的是按 Scout 的优先级读若干实现——用一份预算去分，只会让一方的规模
 * 决定另一方的规模。
 *
 * <h2>本 Task 不提供默认值</h2>
 *
 * <p>刻意没有 {@code mvpDefault()} 之类的工厂：最终的运行时数值应当在流程接通、并且有真实
 * 证据说明「读多少才够」之后再定。现在写一个 64 / 128 / 256 KB 之类的常量，等于把一个没有
 * 依据的数字固化成默认行为。
 *
 * <h2>单位</h2>
 *
 * <p>上限按字节计，因为文件大小来自列目录时的 blob metadata，而不是读出来的内容长度。
 * 因此这里的取舍发生在读取**之前**——这正是它存在的意义：既定的预算应当约束真实工作量，
 * 而不只是约束最终送进模型的内容量。
 *
 * @param maxFiles      该通道最多读多少个文件，必须大于 0
 * @param maxFileBytes  单个文件的最大字节数，必须大于 0
 * @param maxTotalBytes 该通道所有文件内容的累计最大字节数，不得小于 {@code maxFileBytes}
 */
public record RepositoryMaterialBudget(int maxFiles, int maxFileBytes, int maxTotalBytes) {

    public RepositoryMaterialBudget {
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("maxFiles 必须大于 0: " + maxFiles);
        }
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes 必须大于 0: " + maxFileBytes);
        }
        if (maxTotalBytes < maxFileBytes) {
            throw new IllegalArgumentException(
                    "maxTotalBytes 不能小于 maxFileBytes: "
                            + maxTotalBytes + " < " + maxFileBytes);
        }
    }
}
