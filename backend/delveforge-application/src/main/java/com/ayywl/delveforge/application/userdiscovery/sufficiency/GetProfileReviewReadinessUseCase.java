package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

/** 只读恢复本版服务端充分性；null 表示未保存通过结果，而不是评估为不足。 */
public final class GetProfileReviewReadinessUseCase {

    private final UserProfileRepository repository;

    public GetProfileReviewReadinessUseCase(UserProfileRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository);
    }

    public Readiness get(UserProfileId id) {
        UserProfile profile = UserProfileCandidates.loadCopy(repository, id);
        return new Readiness(id.value(), profile.revision(),
                repository.hasSufficientAssessment(id, profile.revision()) ? true : null);
    }

    public record Readiness(String profileId, int revision, Boolean sufficient) {
    }
}
