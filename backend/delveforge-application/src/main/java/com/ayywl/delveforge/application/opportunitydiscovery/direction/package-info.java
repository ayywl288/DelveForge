/**
 * Product Direction Discovery 的 AI 边界：Prompt 构造、严格解析与引用校验。
 *
 * <pre>
 * Confirmed UserProfile + RepositoryProfile 1..N
 *         ↓
 * DirectionDiscoveryInputs     可信输入，以及模型可以引用什么
 *         ↓
 * DirectionDiscoveryExtraction 构造 Prompt、调用 AI Gateway
 *         ↓
 * DirectionDiscoveryProposalParser  严格解析模型输出
 *         ↓
 * DirectionProposal[]          仍然只是尚未被接受的提议
 * </pre>
 *
 * <p>提议不会在这里成为领域状态：是否构成合法 Product Direction 由下一阶段的
 * {@code ProductDirectionDiscoveryService} 判定（DOMAIN_MODEL.md §12.4）。
 *
 * <p>Provider 专有类型不出现在本包：{@code AiGateway} 返回的已经是模型原始内容，
 * Provider 响应信封的解析属于 Infrastructure。
 */
package com.ayywl.delveforge.application.opportunitydiscovery.direction;
