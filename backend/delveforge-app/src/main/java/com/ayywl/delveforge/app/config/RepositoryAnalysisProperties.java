package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryMaterialBudget;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionNavigationLimits;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionRecursionBudget;
import com.ayywl.delveforge.application.repositoryanalysis.region.ScoutCallBudget;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Repository Analysis 的运行时配置，前缀 {@code delveforge.repository-analysis}。
 *
 * <h2>这些是运维调参值，不是领域规则</h2>
 *
 * <p>DOMAIN_MODEL 只要求分析针对一个确定的软件状态并形成可追溯结论，没有规定读多少、
 * 读多大。下面这些上限决定一次分析能看到多少，改变它们不需要改领域模型，但会改变分析结果
 * 的质量。因此它们必须是**可配置的**，而不是刻进业务逻辑的常量（AGENTS.md §8.9）。
 *
 * <p>当前取值来自 Repository Understanding 的第二轮真实验证准备，属于操作调参；
 * Round 2 smoke test 之后再按真实证据调整。形状刻意保持扁平（两条通道各一组同类字段），
 * 便于那时直接改数值。
 *
 * <h2>默认值在配置里，不在这里</h2>
 *
 * <p>与项目其它配置一致：默认值位于 {@code delveforge-app} 的 {@code application.yml}，
 * 代码里不做兜底。配置缺失时启动即失败，而不是静默使用一个意料之外的上限。
 *
 * <p>本类型属于 Interface / Composition Root 一侧，不进入 Domain，也不进入 Application——
 * Application 只认识 {@link RepositoryMaterialBudget} 这样的值对象。
 *
 * @param scout          File Scout 调用相关的上限
 * @param region         Region 分层导航的守卫上限
 * @param scoutCalls     整次分析的 Scout 调用总数上限（Region Scout + File Scout）
 * @param foundation     基础材料通道的预算
 * @param targetedSource 定向源码通道的预算
 */
@ConfigurationProperties("delveforge.repository-analysis")
public record RepositoryAnalysisProperties(Scout scout,
                                          Region region,
                                          ScoutCalls scoutCalls,
                                          Lane foundation,
                                          Lane targetedSource) {

    private static final String PREFIX = "delveforge.repository-analysis";

    public RepositoryAnalysisProperties {
        if (scout == null) {
            throw new IllegalArgumentException("必须配置 " + PREFIX + ".scout");
        }
        if (region == null) {
            throw new IllegalArgumentException("必须配置 " + PREFIX + ".region");
        }
        if (scoutCalls == null) {
            throw new IllegalArgumentException("必须配置 " + PREFIX + ".scout-calls");
        }
        if (foundation == null) {
            throw new IllegalArgumentException("必须配置 " + PREFIX + ".foundation");
        }
        if (targetedSource == null) {
            throw new IllegalArgumentException("必须配置 " + PREFIX + ".targeted-source");
        }
    }

    /**
     * Scout 调用相关的上限。
     *
     * @param maxCatalogBytes Scout 目录载荷的字节上限；超过即失败关闭，不截断、不采样
     */
    public record Scout(int maxCatalogBytes) {

        public Scout {
            if (maxCatalogBytes <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + ".scout.max-catalog-bytes 必须大于 0: " + maxCatalogBytes);
            }
        }
    }

    /**
     * Region 分层导航的守卫上限（ADR-0005 的**导航预算**）。
     *
     * <p>与下面两条通道的材料预算分开：材料预算约束最终读多少源码，导航预算约束探索本身
     * 能走多远（因而约束 AI 调用的增长）。两者不互相占用。
     *
     * <p>前两项约束**一次** Region Scout 调用；后两项约束**一次仓库分析**里的递归本身。
     * 后两项彼此独立——轮数管深度，总调用数管「深度 × 宽度」；总调用数小于单分支轮数上限
     * 是合法组合，那只是说明深度上限不是先耗尽的那一个（ADR-0005 也没有规定二者的大小关系）。
     * ADR-0005 的终态分支数守卫尚未需要（File Scout 执行属于后续步骤），因此不在这里预留。
     *
     * @param maxCatalogBytes    Region Catalog 载荷的 UTF-8 字节上限
     * @param maxSelectedRegions 一次 Region Scout 最多可选多少个区域
     * @param maxRoundsPerBranch 沿单条分支最多几次 Region Scout 调用
     * @param maxScoutCalls      一次仓库分析最多几次 **Region** Scout 调用
     */
    public record Region(int maxCatalogBytes,
                         int maxSelectedRegions,
                         int maxRoundsPerBranch,
                         int maxScoutCalls) {

        public Region {
            if (maxCatalogBytes <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + ".region.max-catalog-bytes 必须大于 0: " + maxCatalogBytes);
            }
            if (maxSelectedRegions <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + ".region.max-selected-regions 必须大于 0: "
                                + maxSelectedRegions);
            }
            if (maxRoundsPerBranch <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + ".region.max-rounds-per-branch 必须大于 0: "
                                + maxRoundsPerBranch);
            }
            if (maxScoutCalls <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + ".region.max-scout-calls 必须大于 0: " + maxScoutCalls);
            }
        }

        /** 转成 Application 侧的单次调用守卫值对象。 */
        public RegionNavigationLimits toLimits() {
            return new RegionNavigationLimits(maxCatalogBytes, maxSelectedRegions);
        }

        /** 转成 Application 侧的递归守卫值对象。 */
        public RegionRecursionBudget toRecursionBudget() {
            return new RegionRecursionBudget(maxRoundsPerBranch, maxScoutCalls);
        }
    }

    /**
     * 整次分析的 **Scout 调用总数**上限（ADR-0005）。
     *
     * <p>两条通道合并计数：分层下降多问几次 Region Scout，留给终态分支的 File Scout 额度
     * 就少几次。早期草案给终态分支单独设上限，那会让两者各自有独立额度、彼此不相干。
     *
     * <p>它是**整次分析**的守卫，与 {@code region} 那几个「单次调用 / Region Scout」的守卫
     * 不同层。默认值由「Region Scout ≤ 12」的最坏包络（12 + 6 终态分支）推算而来，
     * 是技术守卫而非已验证的最优值。
     *
     * @param maxTotal 一次仓库分析最多几次 Scout 调用（Region + File）
     */
    public record ScoutCalls(int maxTotal) {

        public ScoutCalls {
            if (maxTotal <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + ".scout-calls.max-total 必须大于 0: " + maxTotal);
            }
        }

        /** 转成 Application 侧的 Scout 调用总数守卫值对象。 */
        public ScoutCallBudget toBudget() {
            return new ScoutCallBudget(maxTotal);
        }
    }

    /**
     * 一条通道的选材预算。
     *
     * <p>两条通道各持一份，互不占用：Foundation 读了多少不会让定向源码少读。
     * 校验规则与 {@link RepositoryMaterialBudget} 一致，错误信息里带上配置键，
     * 便于直接定位到该改哪一行。
     *
     * @param maxFiles      该通道最多读多少个文件
     * @param maxFileBytes  单个文件的最大字节数
     * @param maxTotalBytes 该通道所有文件内容的累计最大字节数
     */
    public record Lane(int maxFiles, int maxFileBytes, int maxTotalBytes) {

        public Lane {
            if (maxFiles <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + " 的 max-files 必须大于 0: " + maxFiles);
            }
            if (maxFileBytes <= 0) {
                throw new IllegalArgumentException(
                        PREFIX + " 的 max-file-bytes 必须大于 0: " + maxFileBytes);
            }
            if (maxTotalBytes < maxFileBytes) {
                throw new IllegalArgumentException(
                        PREFIX + " 的 max-total-bytes 不能小于 max-file-bytes: "
                                + maxTotalBytes + " < " + maxFileBytes);
            }
        }

        /** 转成 Application 侧的预算值对象。 */
        public RepositoryMaterialBudget toBudget() {
            return new RepositoryMaterialBudget(maxFiles, maxFileBytes, maxTotalBytes);
        }
    }
}
