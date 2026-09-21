/**
 * User Discovery 各子包共用的协作单元。
 *
 * <pre>
 * ProfilePromptContext   把当前 Profile 内容组织成 AI 请求上下文
 * UserProfileCandidates  构造隔离的候选副本，使改动不落在 Repository 返回的对象上
 * </pre>
 *
 * <p>它们跨越 profile / exploration / sufficiency / review / workflow 多个子包使用，
 * 因此无法保持包级私有。<b>它们是 Application 内部的协作单元</b>：
 * Composition Root 与其它模块不应引用，对外契约由各子包的公开 Use Case 承担。
 */
package com.ayywl.delveforge.application.userdiscovery.shared;
