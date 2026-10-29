package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

import java.util.List;
import java.util.Objects;

/** 只读恢复本版服务端充分性；null 表示未保存通过结果，而不是评估为不足。 */
public final class GetProfileReviewReadinessUseCase {

    private final UserProfileRepository repository;

    public GetProfileReviewReadinessUseCase(UserProfileRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Readiness get(UserProfileId id) {
        UserProfile profile = UserProfileCandidates.loadCopy(repository, id);
        var assessment = repository.findReadinessAssessment(id, profile.revision());
        return new Readiness(id.value(), profile.revision(),
                assessment.map(value -> value.sufficient()).orElseGet(() ->
                        repository.hasSufficientAssessment(id, profile.revision()) ? true : null),
                assessment.map(value -> value.missingAreas()).orElse(List.of()));
    }

    public record Readiness(String profileId, int revision, Boolean sufficient, List<String> missingAreas) {
        public Readiness(String profileId, int revision, Boolean sufficient) {
            this(profileId, revision, sufficient, List.of());
        }
    }
}
