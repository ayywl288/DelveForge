/**
 * Inbound / Outbound Port 抽象。
 *
 * <p>这些接口由 Application 拥有、由 Infrastructure 实现（RULE-ARCH-003、RULE-ARCH-004）。
 * Application 不得直接引用具体 Adapter 类型。按能力分为：
 *
 * <pre>
 * port.ai             AI Gateway 抽象
 * port.workspace      Workspace Gateway 抽象
 * port.persistence    Persistence Repository 抽象
 * </pre>
 */
package com.ayywl.delveforge.application.port;
