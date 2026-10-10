package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

import java.util.List;
import java.util.Objects;

/** 用户按需取得下一问：仅在 EXPLORING 调用一次既有评估器，完全不保存内容、依据或状态。 */
public final class AskDiscoveryQuestionUseCase {

    private final UserProfileRepository repository;
    private final ProfileSufficiencyEvaluator evaluator;

    public AskDiscoveryQuestionUseCase(UserProfileRepository repository, ProfileSufficiencyEvaluator evaluator) {
        this.repository = Objects.requireNonNull(repository);
        this.evaluator = Objects.requireNonNull(evaluator);
    }

    public SufficiencyAssessment ask(UserProfileId id, int revision, List<String> previousQuestions) {
        List<String> questions = DiscoveryQuestions.validate(previousQuestions);
        UserProfile snapshot = UserProfileCandidates.loadCopy(repository, id);
        requireCurrentExploration(snapshot, revision);

        SufficiencyAssessment result = evaluator.evaluateForExploration(snapshot, questions);

        // 长 AI 请求期间若画像已变化，丢弃旧版问题；不把旧评估放到新的审阅/确认版本上。
        requireCurrentExploration(UserProfileCandidates.loadCopy(repository, id), revision);
        return result;
    }

    private static void requireCurrentExploration(UserProfile profile, int revision) {
        profile.requireExplorationAllowed();
        if (profile.revision() != revision) {
            throw new UserProfileStateException("下一问所依据的画像版本已经变化");
        }
    }
}
