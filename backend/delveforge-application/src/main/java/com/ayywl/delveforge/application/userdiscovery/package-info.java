/**
 * User Discovery：从用户自然语言输入形成结构化 User Profile 的 Application 边界。
 *
 * <p>本包只做流程编排：加载领域对象、通过 Port 调用外部能力、调用 Domain 决定状态与
 * revision、持久化结果。内容是否合法、revision 是否推进、状态转换是否允许，全部由
 * {@code UserProfile} Aggregate 判定。
 *
 * <p>按职责分为子包：
 *
 * <pre>
 * profile        Profile Management：创建、读取、结构化更新
 * exploration    用一轮用户输入让 AI 提出 Profile 更新建议
 * sufficiency    判断当前 Profile 的信息是否足以进入 Review
 * review         用户在 Review 阶段做出的决定：确认、继续探索、重新开启探索
 * workflow       把上面几步组合成面向产品的一轮 User Discovery
 * shared         跨子包复用的协作单元，见该包的 package-info
 * </pre>
 *
 * <p>外部（Composition Root 与其它模块）只依赖各子包中的公开类型；
 * {@code shared} 中的类型是 Application 内部协作单元。
 */
package com.ayywl.delveforge.application.userdiscovery;
