/**
 * Sufficiency：判断当前 User Profile 的信息是否足以进入 Review 阶段。
 *
 * <p>AI 只提出「够不够、缺什么、下一问是什么」；{@code ProfileSufficiencyProposal}
 * 里没有状态字段，因此模型无法表达、更无法设置 {@code UserProfileStatus}。
 * 普通对话轮只评估；显式审阅请求满足条件后调用 {@code UserProfile.beginReview()}。
 */
package com.ayywl.delveforge.application.userdiscovery.sufficiency;
