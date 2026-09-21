/**
 * Repository Analysis 的 AI 提议链路：把 Repository 材料交给 AI，解析成结构化提议。
 *
 * <pre>
 * Repository 材料
 *         ↓
 * AiGateway
 *         ↓
 * 原始模型输出
 *         ↓
 * 解析 / 校验
 *         ↓
 * RepositoryAnalysisProposal
 * </pre>
 *
 * <p>提议是 AI 边界上的中间数据，不是领域对象：在被 Aggregate 接受之前，
 * 它不构成任何合法领域状态（RULE-DOM-003、DOMAIN_MODEL.md §14.11）。
 *
 * <p>本包不调用 Workspace，不获取 analyzedRevision，也不创建或保存 RepositoryProfile——
 * 材料由调用方准备好后交进来，结果由调用方决定如何使用。
 */
package com.ayywl.delveforge.application.repositoryanalysis.extraction;
