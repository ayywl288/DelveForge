/**
 * Sufficiency：判断当前 User Profile 的信息是否足以进入 Review 阶段。
 *
 * <p>AI 只提出「够不够、缺什么、下一问是什么」；{@code ProfileSufficiencyProposal}
 * 里没有状态字段，因此模型无法表达、更无法设置 {@code UserProfileStatus}。
 * 是否真的进入 REVIEWING 由 {@code UserProfile.beginReview()} 依据 §6.1 判断。
 */
package com.ayywl.delveforge.application.userdiscovery.sufficiency;
