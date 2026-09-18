package com.ayywl.delveforge.domain.user;

/**
 * User Profile 的生命周期状态（DOMAIN_MODEL.md §6.1）。
 *
 * <p>本类型只表达状态本身，以及状态对 Profile 内容修改的约束。§6.1 定义的转换
 * 全部由 Aggregate Root 提供入口：
 *
 * <pre>
 * EXPLORING → REVIEWING   信息足够           UserProfile.beginReview
 * REVIEWING → EXPLORING   继续探索           UserProfile.continueDiscovery
 * REVIEWING → REVIEWING   用户纠正 Profile   内容更新本身，无需状态转移入口
 * REVIEWING → CONFIRMED   用户确认 Profile   UserProfile.confirm（须携带所确认的 revision）
 * CONFIRMED → EXPLORING   重新开始探索       UserProfile.reopenDiscovery
 * </pre>
 *
 * <p>转换入口一律放在 Aggregate Root 上，本类型不提供转换方法。
 */
public enum UserProfileStatus {

    /** 系统仍在收集、分析或补充与项目发现相关的用户信息。 */
    EXPLORING(true),

    /** 当前 Profile 已形成可供用户检查的结构化版本。 */
    REVIEWING(true),

    /** 用户明确确认当前 Profile 可以作为项目方向发现依据。 */
    CONFIRMED(false);

    private final boolean allowsProfileUpdate;

    UserProfileStatus(boolean allowsProfileUpdate) {
        this.allowsProfileUpdate = allowsProfileUpdate;
    }

    /**
     * 该状态下是否允许修改 User Profile 的领域内容（包括其判断依据 Evidence）。
     *
     * <p>EXPLORING 与 REVIEWING 允许：§8.1 要求 Profile 处于「允许继续探索的状态」，
     * 且 §6.1 明确 REVIEWING → REVIEWING 表示「用户纠正 Profile」。
     *
     * <p>CONFIRMED 不允许：§6.1 中 CONFIRMED 唯一的出边是「重新开始探索」，
     * 即修改已确认 Profile 的前提是先重新进入探索，而不是直接改写。
     */
    public boolean allowsProfileUpdate() {
        return allowsProfileUpdate;
    }
}
