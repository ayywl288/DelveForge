package com.ayywl.delveforge.application.userdiscovery.workflow;

import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.domain.user.UserProfile;

/**
 * 一轮 User Discovery 的结果。
 *
 * <p>它把这一轮实际产生的两个结果放在一起：
 *
 * <pre>
 * profile      本轮结束时的 User Profile（内容已更新，状态已由 Domain 决定）
 * sufficiency  同一轮的充分性结论：够不够、缺什么、下一问是什么
 * </pre>
 *
 * <p>两个字段描述的是同一时刻的同一个候选 Profile，因此调用方不需要自己把「更新后的
 * Profile」与「针对哪个版本做的评估」对应起来。
 *
 * <p>它不是领域对象，也没有独立身份与生命周期：评估历史当前不持久化（ROADMAP M1 只要求
 * 「判断是否足够」，未要求历史）。
 *
 * @param profile     本轮结束时的 User Profile
 * @param sufficiency 同一轮的充分性结论
 */
public record UserDiscoveryTurn(UserProfile profile, SufficiencyAssessment sufficiency) {

    public UserDiscoveryTurn {
        if (profile == null) {
            throw new IllegalArgumentException("UserDiscoveryTurn 必须包含 profile");
        }
        if (sufficiency == null) {
            throw new IllegalArgumentException("UserDiscoveryTurn 必须包含 sufficiency");
        }
    }
}
