package com.ayywl.delveforge.app.api.evidence;

import com.ayywl.delveforge.domain.evidence.EvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;

/**
 * {@code EvidenceOrigin} 在 HTTP 接口上的表示：这条依据出自哪一份分析、哪一版用户画像。
 *
 * <p>与领域侧一样，出处信息本身是响应的一部分——没有它，两条内容相同的依据就无法区分
 * 各自来自哪里，「这个方向凭什么这么说」也回答不了（§3.6、INV-D06）。
 *
 * <h2>为什么是一个带 kind 的扁平形状</h2>
 *
 * <pre>
 * {"kind": "USER_PROFILE",       "userProfileId": "…", "userProfileRevision": 3}
 * {"kind": "REPOSITORY_PROFILE", "repositoryProfileId": "…"}
 * </pre>
 *
 * <p>{@code kind} 显式给出当前是哪一类出处；不属于该类的字段为 {@code null}。
 * 不用 Jackson 的多态注解，是为了让契约本身（而不是框架配置）说明响应的形状，
 * 也与其余 DTO 的普通 record 风格一致。
 *
 * <p>构造时校验字段组合：{@code kind} 与字段必须自洽。这样「构造出了一个自己都说不清
 * 出处的负载」不可能发生，而不是等到调用方发现响应里少了东西。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象。
 *
 * @param kind                出处类别，不得为 {@code null}
 * @param userProfileId       该依据所属的 User Profile；仅 {@code USER_PROFILE} 时有值
 * @param userProfileRevision 该依据所属的 User Profile 版本；仅 {@code USER_PROFILE} 时有值，
 *                            且不小于 1
 * @param repositoryProfileId 该依据所属的 Repository Profile；仅 {@code REPOSITORY_PROFILE} 时有值
 */
public record EvidenceOriginPayload(
        EvidenceOriginKind kind,
        String userProfileId,
        Integer userProfileRevision,
        String repositoryProfileId) {

    public EvidenceOriginPayload {
        if (kind == null) {
            throw new IllegalArgumentException("Evidence Origin 必须指定 kind");
        }
        switch (kind) {
            case USER_PROFILE -> {
                if (userProfileId == null || userProfileId.isBlank()) {
                    throw new IllegalArgumentException(
                            "USER_PROFILE 来源必须给出 userProfileId");
                }
                if (userProfileRevision == null || userProfileRevision < 1) {
                    throw new IllegalArgumentException(
                            "USER_PROFILE 来源必须给出确定的 userProfileRevision");
                }
                requireAbsent(repositoryProfileId, "repositoryProfileId", kind);
            }
            case REPOSITORY_PROFILE -> {
                if (repositoryProfileId == null || repositoryProfileId.isBlank()) {
                    throw new IllegalArgumentException(
                            "REPOSITORY_PROFILE 来源必须给出 repositoryProfileId");
                }
                requireAbsent(userProfileId, "userProfileId", kind);
                requireAbsent(userProfileRevision, "userProfileRevision", kind);
            }
        }
    }

    /**
     * 把领域出处映射为它的接口表示。
     *
     * <p>映射放在负载自己的类上：它被 Product Direction 的响应使用，
     * 也与 {@link EvidencePayload} 一样属于 Evidence 的接口表示。
     *
     * @param origin 领域侧出处，不得为 {@code null}
     */
    public static EvidenceOriginPayload from(EvidenceOrigin origin) {
        return switch (origin) {
            case UserProfileEvidenceOrigin userProfile -> new EvidenceOriginPayload(
                    EvidenceOriginKind.USER_PROFILE,
                    userProfile.userProfileId().value(),
                    userProfile.userProfileRevision(),
                    null);
            case RepositoryProfileEvidenceOrigin repositoryProfile -> new EvidenceOriginPayload(
                    EvidenceOriginKind.REPOSITORY_PROFILE,
                    null,
                    null,
                    repositoryProfile.repositoryProfileId().value());
        };
    }

    /**
     * 不属于当前 {@code kind} 的字段必须缺席。
     *
     * <p>放任它出现会让同一个负载同时声称两种出处，调用方无从判断该信哪一个。
     */
    private static void requireAbsent(Object value, String field, EvidenceOriginKind kind) {
        if (value != null) {
            throw new IllegalArgumentException(
                    kind + " 来源不应给出 " + field);
        }
    }
}
