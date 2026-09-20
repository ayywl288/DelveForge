/**
 * Software Asset 的登记与查询。
 *
 * <p>Repository Analysis 只接收已经确定的 Software Asset（DOMAIN_MODEL.md §14.7），
 * 因此「用户指定一个本地 Git Repository，系统登记对应 Software Asset」是这条链路的入口。
 *
 * <p>本包只负责资产元数据：登记不访问文件系统，也不判断 location 是否真实存在，
 * 因此不依赖任何 Workspace 能力。只读访问 Repository 属于 Analysis 本身，
 * 在后续 Task 中引入（RULE-ARCH-010）。
 */
package com.ayywl.delveforge.application.repositoryanalysis.asset;
