/**
 * Inbound / Outbound Port 抽象。
 *
 * <p>包含 AI Gateway、Workspace Gateway 与 Persistence Repository 接口（RULE-ARCH-003）。
 * 这些接口由 Application 拥有，由 Infrastructure 实现；Application 不得直接引用具体 Adapter。
 */
package com.ayywl.delveforge.application.port;
