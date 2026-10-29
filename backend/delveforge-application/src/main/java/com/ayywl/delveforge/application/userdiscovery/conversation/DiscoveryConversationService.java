package com.ayywl.delveforge.application.userdiscovery.conversation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository.Command;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository.History;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository.Memory;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository.Operation;
import com.ayywl.delveforge.application.port.persistence.ProjectContextRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.project.ProjectFlowContext;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileExtraction;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AskDiscoveryQuestionUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.application.userdiscovery.workflow.RunUserDiscoveryTurnUseCase;
import com.ayywl.delveforge.application.userdiscovery.workflow.UserDiscoveryTurn;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 持久问答的产品入口；AI 在短事务之外运行，未知结果按请求身份查询，不盲目重发。 */
public final class DiscoveryConversationService {

    private final UserProfileRepository profiles;
    private final DiscoveryConversationRepository conversations;
    private final ProjectContextRepository projects;
    private final RunUserDiscoveryTurnUseCase turns;
    private final AskDiscoveryQuestionUseCase questions;

    public DiscoveryConversationService(UserProfileRepository profiles, DiscoveryConversationRepository conversations,
            ProjectContextRepository projects, RunUserDiscoveryTurnUseCase turns, AskDiscoveryQuestionUseCase questions) {
        this.profiles = profiles;
        this.conversations = conversations;
        this.projects = projects;
        this.turns = turns;
        this.questions = questions;
    }

    public Operation submit(UserProfileId id, Command supplied) {
        String requestId = supplied.requestId() == null ? UUID.randomUUID().toString() : supplied.requestId();
        ProjectFlowContext.requireId(requestId);
        if (!"TURN".equals(supplied.kind()) && !"QUESTION".equals(supplied.kind())) {
            throw new IllegalArgumentException("未知探索操作");
        }
        if ("TURN".equals(supplied.kind())) {
            ProfileExtraction.requireUserInput(supplied.input());
        }
        requireProject(id, supplied.projectId());
        Command command = new Command(requestId, supplied.kind(), supplied.input(), supplied.revision(),
                supplied.projectId());
        String fingerprint = fingerprint(command);
        Operation previous = conversations.operation(id, requestId).orElse(null);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) {
                throw new UserProfileStateException("请求编号已用于其他内容，请查询原请求结果");
            }
            return previous;
        }
        UserProfile expected = UserProfileCandidates.loadCopy(profiles, id);
        expected.requireExplorationAllowed();
        if (command.revision() != null && expected.revision() != command.revision()) {
            throw new UserProfileStateException("探索所依据的画像版本已经变化");
        }
        Operation claimed = conversations.begin(expected, command, fingerprint);
        // 并发相同请求只有一个执行者；其他调用读取同一个持久状态，不再次调用模型。
        if (!"STARTED".equals(claimed.state())) {
            return claimed;
        }
        try {
            Memory memory = conversations.memory(id);
            DiscoveryReadiness readiness = conversations.readiness(id, expected.revision()).orElse(null);
            if ("QUESTION".equals(command.kind()) && memory.unansweredQuestion() != null) {
                throw new UserProfileStateException("还有未回答的问题，请先回答或明确跳过");
            }
            UserProfile candidate;
            SufficiencyAssessment result;
            if ("TURN".equals(command.kind())) {
                UserDiscoveryTurn turn = turns.prepare(expected, command.input(), memory, readiness);
                candidate = turn.profile();
                result = turn.sufficiency();
            } else {
                candidate = UserProfileCandidates.copyOf(expected);
                result = questions.prepare(candidate, memory, readiness);
            }
            if (result.nextQuestion() != null && (conversations.previouslyAsked(id, result.nextQuestion())
                    || memory.unansweredQuestion() != null && "OPEN".equals(result.previousQuestionState()))) {
                if ("QUESTION".equals(command.kind())) {
                    throw new AiGatewayException("没有未重复的可用问题");
                }
                result = new SufficiencyAssessment(result.sufficient(), result.missingAreas(), null,
                        result.profileStatus(), result.assistantReply(), null, result.deferredAreas(),
                        result.previousQuestionState(), result.resumedAreas());
            }
            return conversations.complete(expected, candidate, command, result);
        } catch (RuntimeException failure) {
            try {
                conversations.fail(id, requestId);
            } catch (RuntimeException persistenceFailure) {
                // 无法记录失败时仍保留 PENDING，不能假称请求已失败或安全重发。
                failure.addSuppressed(persistenceFailure);
            }
            throw failure;
        }
    }

    public History history(UserProfileId id, String projectId, Long before) {
        UserProfileCandidates.loadCopy(profiles, id);
        requireProject(id, projectId);
        if (before != null && before <= 0) {
            throw new IllegalArgumentException("历史游标必须为正整数");
        }
        return conversations.history(id, before);
    }

    public Operation status(UserProfileId id, String requestId, String projectId) {
        UserProfileCandidates.loadCopy(profiles, id);
        ProjectFlowContext.requireId(requestId);
        requireProject(id, projectId);
        return conversations.operation(id, requestId).orElse(
                new Operation(requestId, null, projectId, null, "NOT_FOUND", null, null));
    }

    private void requireProject(UserProfileId id, String projectId) {
        if (projectId == null) {
            return;
        }
        ProjectFlowContext.requireId(projectId);
        ProjectFlowContext project = projects.findById(projectId)
                .orElseThrow(() -> new UserProfileStateException("探索项目不存在"));
        if (!id.value().equals(project.userProfileId()) || project.deletedAt() != null) {
            throw new UserProfileStateException("项目与探索画像不匹配");
        }
    }

    public Operation cancel(UserProfileId id, String requestId, String projectId) {
        UserProfileCandidates.loadCopy(profiles, id);
        ProjectFlowContext.requireId(requestId);
        requireProject(id, projectId);
        return conversations.cancel(id, requestId);
    }

    private static String fingerprint(Command command) {
        try {
            String input = command.kind() + "\n" + command.revision() + "\n" + command.projectId()
                    + "\n" + command.input();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
