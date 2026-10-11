/**
 * Software Asset 的登记与查询。
 *
 * <p>Repository Analysis 只接收已经确定的 Software Asset（DOMAIN_MODEL.md §14.7），
 * 因此「用户指定一个本地 Git Repository，系统登记对应 Software Asset」是这条链路的入口。
 *
 * <p>本包只负责资产元数据：新登记经 Repository 原子去重，位置比较由只读 Workspace
 * 身份能力实现；不读取源码或 LICENSE。用户显式更新授权，不重新运行 Analysis。
 */
package com.ayywl.delveforge.application.repositoryanalysis.asset;
