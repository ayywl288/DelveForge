package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.application.userdiscovery.conversation.DiscoveryReadiness;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileUpdateProposal;
import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.DialogueAction;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 对话与请求结果的持久化边界；完成操作必须与 Profile 和充分性一起原子提交。 */
public interface DiscoveryConversationRepository {

    record Command(String requestId, String kind, String input, Integer revision, String projectId) {
    }

    record Operation(String requestId, String kind, String projectId, String fingerprint, String state,
            Integer revision, SufficiencyAssessment result, ProfileUpdateProposal proposal) {
        public Operation(String requestId, String kind, String projectId, String fingerprint, String state,
                Integer revision, SufficiencyAssessment result) {
            this(requestId, kind, projectId, fingerprint, state, revision, result, null);
        }
    }

    record Message(long sequence, String requestId, String role, String text, String question,
            String area, String questionState, Instant createdAt, String projectId, DialogueAction nextAction) {
    }

    record History(List<Message> messages, Long before, String pendingRequestId, String unansweredQuestion, String invitationRequestId, ProfileUpdateProposal pendingProposal) {
    }

    record Topic(String area, String question, String state) {
    }

    record Memory(List<DiscoveryContext.Message> history, List<String> questions,
            List<String> deferredAreas, List<Topic> topics, String unansweredQuestion, boolean invitationPresented) {
    }

    Optional<Operation> operation(UserProfileId id, String requestId);

    Operation begin(UserProfile expected, Command command, String fingerprint);

    /** 返回已记录结果；与画像、问答和当前版本充分性处于同一提交事务。 */
    default Operation complete(UserProfile expected, UserProfile candidate, Command command, SufficiencyAssessment result) {
        return complete(expected, candidate, command, result, null);
    }

    Operation complete(UserProfile expected, UserProfile candidate, Command command,
            SufficiencyAssessment result, ProfileUpdateProposal proposal);

    void fail(UserProfileId id, String requestId);

    /** 显式放弃未完成结果；未知编号也留下拒绝迟到提交的记录，已成功的结果不能被撤销。 */
    Operation cancel(UserProfileId id, String requestId);

    History history(UserProfileId id, Long before);

    Memory memory(UserProfileId id);

    Optional<DiscoveryReadiness> readiness(UserProfileId id, int revision);

}
