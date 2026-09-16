/**
 * Evidence：支撑领域判断的可追溯依据（DOMAIN_MODEL.md §3.6）。
 *
 * <p>Evidence 回答「系统为什么得出这个结论」，可嵌入 User Profile、Repository
 * Profile、Product Direction、Evolution Plan 等多个 Aggregate。它是跨业务边界共享的
 * 领域 Value Object，因此按 Domain Concept 单独成包，而不是放进任一 Feature 包
 * （RULE-ARCH-006），也不放进无明确职责的通用包（RULE-ARCH-007）。
 *
 * <p>本包只承载 Evidence 自身的领域语义。Evidence 如何被产生（用户交互、AI 提取）、
 * 如何持久化、如何校验，属于 Application / Infrastructure 的职责，不在本包内。
 */
package com.ayywl.delveforge.domain.evidence;
