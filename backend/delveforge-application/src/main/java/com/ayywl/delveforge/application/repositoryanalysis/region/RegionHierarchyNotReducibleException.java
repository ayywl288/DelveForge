package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * 某个节点超出 File Catalog 预算，而按目录结构**已经无法再缩小**。
 *
 * <p>两种情况：
 *
 * <pre>
 * 该节点下没有任何含源码的子目录，不能再往下钻
 * 直接位于该节点的源码文件本身就超过预算，而它们没有更细的结构可以分
 * </pre>
 *
 * <p>失败的稳定标识：{@code HIERARCHY_NOT_REDUCIBLE}。
 *
 * <p>这是失败关闭，不是降级：既不采样、不截断，也不退回「把整份 oversized 目录直接发给
 * File Scout」。扁平地放着数千个文件的目录，在本版本里就是处理不了——如实说出来，
 * 比悄悄换一种做法更可信（ADR-0005）。
 */
public class RegionHierarchyNotReducibleException extends RuntimeException {

    /** 目录结构已无法再缩小时使用的稳定标识。 */
    public static final String HIERARCHY_NOT_REDUCIBLE = "HIERARCHY_NOT_REDUCIBLE";

    public RegionHierarchyNotReducibleException(String detail) {
        super(detail);
    }
}
