/**
 * User Discovery Use Case 与流程编排。
 *
 * <p>当前只包含维护 User Profile 的最小 Use Case：创建空的 Profile，以及按结构化输入
 * 更新已有 Profile。两者都只转发给 {@code UserProfile} Aggregate 的既有领域行为，
 * 不在本层判断内容是否合法或是否推进 revision。
 *
 * <p>对话探索、Sufficiency Assessment、状态转换与 Review / Confirm 流程尚未实现。
 */
package com.ayywl.delveforge.application.userdiscovery;
