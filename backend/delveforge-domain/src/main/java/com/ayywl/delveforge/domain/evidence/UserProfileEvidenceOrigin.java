package com.ayywl.delveforge.domain.evidence;

import com.ayywl.delveforge.domain.user.UserProfileId;

/**
 * 该 Evidence 来自某一版 User Profile。
 *
 * <p>带上 revision 而不是只记 id：一条 Product Direction 必须能够追溯到确定的
 * {@code UserProfileId + revision}（INV-D01、INV-D08），而依据与内容必须来自同一版——
 * 只记 id 会让「依据来自哪一版」重新变得不可回答。
 *
 * @param userProfileId       该依据所属的 User Profile，不得为 {@code null}
 * @param userProfileRevision 该依据所属的版本，不得小于 1
 */
public record UserProfileEvidenceOrigin(UserProfileId userProfileId, int userProfileRevision)
        implements EvidenceOrigin {

    public UserProfileEvidenceOrigin {
        if (userProfileId == null) {
            throw new IllegalArgumentException(
                    "User Profile Evidence Origin 必须指定 userProfileId");
        }
        if (userProfileRevision < 1) {
            throw new IllegalArgumentException(
                    "User Profile Evidence Origin 的 revision 必须是确定的版本: "
                            + userProfileRevision);
        }
    }
}
