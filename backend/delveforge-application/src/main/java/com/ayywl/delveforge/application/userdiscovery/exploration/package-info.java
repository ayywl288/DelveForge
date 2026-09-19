/**
 * Exploration：用一轮用户自然语言输入驱动 Profile 更新。
 *
 * <pre>
 * 当前 Profile + 本轮输入
 *         ↓
 * AI Gateway → 结构化 UserProfileProposal
 *         ↓
 * UserProfile Aggregate（决定建议是否被接受、revision 是否推进）
 * </pre>
 *
 * <p>模型提出的建议不构成合法领域状态；Evidence 的可追溯信息由本层补齐，
 * 模型无法构造无法追溯的依据。
 */
package com.ayywl.delveforge.application.userdiscovery.exploration;
