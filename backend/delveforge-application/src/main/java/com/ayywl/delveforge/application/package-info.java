/**
 * Application 层：Use Case、Application Service 与流程编排。
 *
 * <p>本层按业务边界组织为 Feature 包：
 *
 * <pre>
 * userdiscovery          User Discovery 流程
 * repositoryanalysis     Repository Analysis 流程
 * opportunitydiscovery   Opportunity Discovery 流程
 * evolution              Evolution Planning / Execution / Verification 流程
 * port                   外部能力抽象（AI / Workspace / Persistence）
 * </pre>
 *
 * <h2>Orchestration 职责边界</h2>
 *
 * <p>Application 负责“按什么顺序做”：
 *
 * <pre>
 * 加载所需的领域对象
 * 通过 Port 调用外部能力
 * 调用 Domain 完成规则校验与状态转换
 * 持久化结果
 * </pre>
 *
 * <p>Application 不负责“什么样的内容才合法”：
 *
 * <ul>
 *   <li>业务合法性由 Domain 判定。Application 不得绕过 Aggregate Root /
 *       Domain Policy 直接改写领域状态（RULE-DOM-002）。
 *   <li>AI 返回的内容在本层完成解析与校验后才交给 Domain，
 *       模型输出本身不构成领域状态（RULE-DOM-003）。
 *   <li>本地软件操作一律通过 Workspace Port 完成，
 *       本层不得直接执行 Git / Shell / Filesystem / Build / Test（RULE-ARCH-009）。
 * </ul>
 *
 * <p>依赖方向：本层可以依赖 Domain，不得依赖 Infrastructure 或 App（RULE-ARCH-001）。
 * 外部能力通过 {@code port} 包中的接口调用，实现由 Infrastructure 提供。
 *
 * <p>当前尚未建立统一的 Use Case / Command / Handler 抽象。具体 Use Case
 * 从 M1 开始按业务语义逐个建立；只有在多个真实 Use Case 暴露出稳定共同模式后，
 * 才考虑提取通用约定，避免提前固化错误形状。
 */
package com.ayywl.delveforge.application;
