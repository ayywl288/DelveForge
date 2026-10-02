package com.ayywl.delveforge.application.repositoryanalysis.workflow;

/**
 * 该软件资产当前无法被分析。
 *
 * <p>与「请求不合法」和「服务端故障」都不同：请求本身可以理解，资产也确实存在，
 * 只是在当前状态下分析不出任何东西。当前覆盖三种情形，它们的对外含义相同——
 * 调用方需要改变资产或其所在仓库的状态（或调整分析上限），而不是改请求、也不是等重试：
 *
 * <pre>
 * 位置不是可读取的本地 Repository    资产指向的位置当前不是一个 Git Repository
 * 没有可分析的材料                   该 Repository 在选材策略下没有任何可读的文件
 * 源码目录的形状超出当前分析方式      flat 目录超限后分层仍无法把它压进预算
 *                                    （具体是哪个守卫见 cause）
 * </pre>
 *
 * <p>几种情况各自的原因写在 message 里，便于定位；对外只表达「当前无法分析」。
 * 如果将来需要把它们映射成不同的协议错误，再拆成多个类型——
 * 现在拆开只会让调用方对同一类失败做两次相同的处理。
 *
 * <p>刻意不复用 {@link IllegalArgumentException}：那是「请求不合法」，
 * 把它用在这里会让接口层返回 400，暗示调用方改请求就能成功。
 * 也不复用 {@code WorkspaceException}：后者表示本地能力调用失败（服务端一侧的问题），
 * 而这里是资产当前状态不允许分析。
 *
 * <h2>为什么可以带 cause</h2>
 *
 * <p>分层 Scout 的几个守卫（目录超限 / 轮数 / 调用数 / 结构不可再分）对调用方是同一个
 * 含义，但对定位的人不是。把它们统一成本类型、同时把原始守卫放进 cause，两种情况都照顾到：
 * 对外只有一种语义，日志与栈里仍能看出是哪一条守卫。
 */
public class RepositoryNotAnalyzableException extends RuntimeException {

    public RepositoryNotAnalyzableException(String message) {
        super(message);
    }

    public RepositoryNotAnalyzableException(String message, Throwable cause) {
        super(message, cause);
    }
}
