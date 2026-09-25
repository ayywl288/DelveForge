/**
 * Product Direction Discovery 的 AI 边界：Prompt 构造、严格解析与引用解析。
 *
 * <pre>
 * Confirmed UserProfile + RepositoryProfile 1..N
 *         ↓
 * DirectionDiscoveryInputs        可信输入，以及模型可以引用什么
 *         ↓
 * DirectionDiscoveryExtraction    构造 Prompt、调用 AI Gateway
 *         ↓
 * DirectionDiscoveryProposalParser   严格解析模型输出
 *         ↓
 * AiDirectionProposal[]            AI 通信协议的一侧，带着临时 Evidence 引用
 *         ↓
 * DirectionProposalResolver       把引用换回真实依据
 *         ↓
 * DirectionProposal[]             领域侧的提案，依据已可追溯
 * </pre>
 *
 * <p><b>边界规则：</b>{@code U-E1} / {@code R2-E3} 这类引用只在一次 AI 调用中有意义，
 * 不得越过 Application → Domain 边界。Domain 不理解引用编号、Prompt 编号方式或 JSON
 * 字段约定——它只看到真实 Evidence 与这些依据各自的来源。
 *
 * <p>提案不会在这里成为领域状态：是否构成合法 Product Direction 由
 * {@code ProductDirectionDiscoveryService} 判定（DOMAIN_MODEL.md §12.4）。
 *
 * <p>Provider 专有类型不出现在本包：{@code AiGateway} 返回的已经是模型原始内容，
 * Provider 响应信封的解析属于 Infrastructure。
 */
package com.ayywl.delveforge.application.opportunitydiscovery.direction;
