/**
 * Review：用户在 Review 阶段做出的决定。
 *
 * <pre>
 * ConfirmUserProfileUseCase   REVIEWING → CONFIRMED   人类决策边界，须携带所依据的 revision
 * ContinueDiscoveryUseCase    REVIEWING → EXPLORING   继续探索
 * ReopenDiscoveryUseCase      CONFIRMED → EXPLORING   重新开启探索
 * </pre>
 *
 * <p>三者都只接受用户标识与（确认所需的）revision，不接受任何 AI 输出：
 * AI 流程没有通往这些动作的路径。
 */
package com.ayywl.delveforge.application.userdiscovery.review;
