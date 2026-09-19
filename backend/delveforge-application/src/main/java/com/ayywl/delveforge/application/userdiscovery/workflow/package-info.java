/**
 * Workflow：把 Exploration 与 Sufficiency 组合成面向产品的一轮 User Discovery。
 *
 * <pre>
 * RunUserDiscoveryTurnUseCase   一次请求完成「提取 → 更新 → 评估 → 必要时进入 Review」
 * UserDiscoveryTurn             该轮的结果：结束时的 Profile + 同一轮的充分性结论
 * </pre>
 *
 * <p>它复用 {@code ProfileExtraction} 与 {@code ProfileSufficiencyEvaluator}，因此不复制
 * Prompt、Parser 或领域规则；整轮作用在同一个候选 Profile 上并只保存一次。
 *
 * <p>这是产品的主要入口；{@code ExploreUserProfileUseCase} 与
 * {@code AssessProfileSufficiencyUseCase} 保留为更细的步骤入口。
 */
package com.ayywl.delveforge.application.userdiscovery.workflow;
