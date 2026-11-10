package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository.Command;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository;
import com.ayywl.delveforge.application.port.persistence.ProjectContextRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.conversation.DiscoveryConversationService;
import com.ayywl.delveforge.application.userdiscovery.conversation.DiscoveryReadiness;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileExtraction;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileRequest;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ConfirmUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ReopenDiscoveryUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AskDiscoveryQuestionUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AssessProfileSufficiencyUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.DiscoveryQuestions;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.ProfileSufficiencyEvaluator;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.application.userdiscovery.workflow.RunUserDiscoveryTurnUseCase;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.ayywl.delveforge.infrastructure.agent.DeepSeekTokenCounterAdapter;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekAiGatewayAdapter;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 不包外层测试事务；验证正式 SQLite 提交、重新创建适配器、受控交错与回滚。无网络或 Docker。 */
@SpringBootTest(classes = DiscoveryConversationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DiscoveryConversationIntegrationTest {

    private static final Path DATABASE = Path.of("target", "test-databases", "conversation-" + UUID.randomUUID(), "db.sqlite");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteUserProfileRepository.class,
            SqliteDiscoveryConversationRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.userprofile")
    static class TestApplication {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE::toString);
    }

    @Autowired
    private UserProfileRepository profiles;

    @Autowired
    private DiscoveryConversationRepository conversations;

    @Autowired
    private DataSource source;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private ObjectMapper json;

    @TempDir Path temporary;

    private final Queue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<AiRequest> calls = new CopyOnWriteArrayList<>();
    private final AiGateway gateway = request -> {
        calls.add(request);
        String response = responses.poll();
        if (response == null) {
            throw new AiGatewayException("受控评估失败");
        }
        return response;
    };

    @Test
    void acceptedConversationAndExactUnansweredQuestionSurviveAdapterRestart() throws Exception {
        UserProfile profile = seed();
        responses.add(reply(true, "首个使用者是谁？", "exploration", "NONE", List.of()));
        Command first = command("QUESTION", null, profile.revision());
        assertEquals("COMPLETED", service(gateway).submit(profile.id(), first).state());
        assertEquals(1, calls.size());
        var restarted = new SqliteDiscoveryConversationRepository(source, manager, profiles, json);
        var history = restarted.history(profile.id(), null);
        assertEquals("首个使用者是谁？", history.unansweredQuestion());
        assertEquals(List.of("agent"), history.messages().stream().map(DiscoveryConversationRepository.Message::role).toList());
        assertNotNull(history.messages().getFirst().createdAt());
        assertEquals(profile.revision(), profiles.findById(profile.id()).orElseThrow().revision());
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, null, null, "ANSWERED", List.of()));
        service(gateway).submit(profile.id(), command("TURN", "我自己使用，先做个人阅读整理", profile.revision()));
        history = restarted.history(profile.id(), null);
        assertEquals(List.of("agent", "user", "agent"), history.messages().stream()
                .map(DiscoveryConversationRepository.Message::role).toList());
        assertTrue(history.messages().get(0).sequence() < history.messages().get(1).sequence());
        assertNull(history.unansweredQuestion());
        assertTrue(restarted.readiness(profile.id(), profile.revision()).orElseThrow().sufficient());
        assertTrue(calls.get(1).messages().get(1).content().contains("首个使用者是谁"));
    }

    @Test
    void retryAndLostResponseRecoverExactlyOneAcceptedTurn() throws Exception {
        UserProfile profile = seed();
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, null, null, "NONE", List.of()));
        Command command = command("TURN", "我还希望能整理个人阅读笔记", profile.revision());
        var service = service(gateway);
        var original = service.submit(profile.id(), command);
        assertEquals(original, service.submit(profile.id(), command));
        assertEquals(original, service.status(profile.id(), command.requestId(), null));
        assertEquals(2, calls.size());
        assertEquals(2, conversations.history(profile.id(), null).messages().size());
        assertThrows(UserProfileStateException.class, () -> service.submit(profile.id(), new Command(
                command.requestId(), "TURN", "另一个输入", profile.revision(), null)));
        assertEquals(2, calls.size());
    }

    @Test
    void databaseFailureRollsBackProfileMessagesReadinessAndSuccessResult() throws Exception {
        UserProfile profile = seed();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TRIGGER conversation_injected_failure BEFORE INSERT ON discovery_message "
                + "WHEN NEW.profile_id='" + profile.id().value() + "' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        responses.add("{\"hasNewFacts\":true,\"constraints\":[\"每周两小时\"],\"replacements\":{\"constraints\":{\"每周五小时\":\"每周两小时\"}},\"evidenceClaims\":[\"用户每周两小时\"]}");
        responses.add(reply(true, null, null, "NONE", List.of()));
        Command command = command("TURN", "改为每周两小时", profile.revision());
        try {
            assertThrows(RuntimeException.class, () -> service(gateway).submit(profile.id(), command));
            assertEquals(profile.revision(), profiles.findById(profile.id()).orElseThrow().revision());
            assertEquals(profile.constraints(), profiles.findById(profile.id()).orElseThrow().constraints());
            assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
            assertTrue(conversations.readiness(profile.id(), profile.revision()).isEmpty());
            assertEquals("FAILED", conversations.operation(profile.id(), command.requestId()).orElseThrow().state());
        } finally {
            jdbc.execute("DROP TRIGGER conversation_injected_failure");
        }
    }

    @Test
    void extractionSuccessAndAssessmentFailureDoNotCommitHalfATurn() {
        UserProfile profile = seed();
        responses.add("{\"hasNewFacts\":true,\"constraints\":[\"新的范围\"],\"replacements\":{\"constraints\":{\"每周五小时\":\"新的范围\"}},\"evidenceClaims\":[\"用户修改范围\"]}");
        Command command = command("TURN", "我想修改为新的范围", profile.revision());
        assertThrows(AiGatewayException.class, () -> service(gateway).submit(profile.id(), command));
        assertEquals(profile.revision(), profiles.findById(profile.id()).orElseThrow().revision());
        assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
        assertEquals("FAILED", conversations.operation(profile.id(), command.requestId()).orElseThrow().state());
    }

    @Test
    void concurrentRetryDoesNotExecuteAgainAndConcurrentProfileChangeRejectsStaleCompletion() throws Exception {
        UserProfile profile = seed();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Command command = command("TURN", "我想继续聊个人阅读笔记", profile.revision());
        var invocation = new AtomicInteger();
        String assessment = reply(true, null, null, "NONE", List.of());
        AiGateway paused = request -> {
            started.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            return invocation.incrementAndGet() == 1 ? "{\"hasNewFacts\":false}" : assessment;
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> service(paused).submit(profile.id(), command));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertEquals("PENDING", service(gateway).submit(profile.id(), command).state());
                assertTrue(calls.isEmpty());
                UserProfile changed = profiles.findById(profile.id()).orElseThrow();
                changed.updateConstraints(List.of("并发修改的范围"));
                profiles.saveIfUnchanged(changed, profile);
                release.countDown();
                var failure = assertThrows(ExecutionException.class,
                        () -> pending.get(10, TimeUnit.SECONDS));
                Assertions.assertInstanceOf(UserProfileStateException.class, failure.getCause());
                assertEquals(List.of("并发修改的范围"), profiles.findById(profile.id()).orElseThrow().constraints());
                assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void exitedOwnerIsInterruptedWithoutAiAndANewExplicitAttemptCanStart() throws Exception {
        UserProfile profile = seed();
        Command old = command("QUESTION", null, profile.revision());
        conversations.begin(profile, old, "fixture");
        new JdbcTemplate(source).update("UPDATE discovery_request SET owner_pid=-1 WHERE profile_id=?",
                profile.id().value());
        assertEquals("INTERRUPTED", service(gateway).status(profile.id(), old.requestId(), null).state());
        assertTrue(calls.isEmpty());
        assertNull(conversations.history(profile.id(), null).pendingRequestId());
        responses.add(reply(true, "想先整理哪类笔记？", "behaviors", "NONE", List.of()));
        assertEquals("COMPLETED", service(gateway).submit(profile.id(), command("QUESTION", null, profile.revision())).state());
    }

    @Test
    void clarificationPreservesTheUnansweredQuestionAndDeclinedAreasSurviveReopening() throws Exception {
        UserProfile profile = seed();
        responses.add(reply(true, "每周能投入多少时间？", "constraints", "NONE", List.of()));
        service(gateway).submit(profile.id(), command("QUESTION", null, profile.revision()));
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, null, null, "ASKED", List.of()));
        service(gateway).submit(profile.id(), command("TURN", "为什么要问投入时间？", profile.revision()));
        assertEquals("每周能投入多少时间？", conversations.history(profile.id(), null).unansweredQuestion());
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, null, null, "DEFERRED", List.of("constraints")));
        service(gateway).submit(profile.id(), command("TURN", "我暂时不想讨论投入时间", profile.revision()));
        var restarted = new SqliteDiscoveryConversationRepository(source, manager, profiles, json);
        assertEquals(List.of("constraints"), restarted.memory(profile.id()).deferredAreas());
        assertNull(restarted.history(profile.id(), null).unansweredQuestion());
        responses.add(reply(true, "每天可以花多少时间？", "constraints", "NONE", List.of()));
        assertThrows(AiGatewayException.class, () -> service(gateway).submit(profile.id(), command("QUESTION", null, profile.revision())));
        assertTrue(calls.getLast().messages().getLast().content().contains("conversationMemory"));
    }

    @Test
    void unchangedNegativeReadinessIsStableAndARealCorrectionReevaluates() throws Exception {
        UserProfile profile = seed();
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(false, null, null, "NONE", List.of()));
        assertFalse(service(gateway).submit(profile.id(), command("TURN", "希望再想想具体的使用场景", 1)).result().sufficient());
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, null, null, "NONE", List.of()));
        responses.add(reply(false, null, null, "NONE", List.of()));
        assertFalse(service(gateway).submit(profile.id(), command("TURN", "暂时没有更多想法", 1)).result().sufficient());
        int callsBeforeReview = calls.size();
        assertFalse(new AssessProfileSufficiencyUseCase(profiles, new ProfileSufficiencyEvaluator(gateway, json))
                .assess(profile.id(), 1).sufficient());
        assertEquals(callsBeforeReview, calls.size());
        responses.add("{\"hasNewFacts\":true,\"projectGoals\":[\"给阅读笔记做全文检索\"],\"replacements\":{\"projectGoals\":{\"六周做个人阅读笔记接口展示后端能力\":\"给阅读笔记做全文检索\"}},\"evidenceClaims\":[\"用户明确阅读检索目标\"]}");
        responses.add(reply(true, null, null, "NONE", List.of()));
        var result = service(gateway).submit(profile.id(), command("TURN", "具体想给阅读笔记做全文检索", 1));
        assertTrue(result.result().sufficient());
        assertTrue(result.revision() > 1);
        assertEquals(UserProfileStatus.EXPLORING, profiles.findById(profile.id()).orElseThrow().status());
    }

    @Test
    void legacyProfilesHaveEmptyHistoryAndAnotherProfileCannotReadTheirMessages() throws Exception {
        UserProfile first = seed();
        UserProfile second = seed();
        assertTrue(conversations.history(first.id(), null).messages().isEmpty());
        responses.add(reply(true, "先做哪些阅读功能？", "projectGoals", "NONE", List.of()));
        service(gateway).submit(first.id(), command("QUESTION", null, 1));
        assertTrue(conversations.history(second.id(), null).messages().isEmpty());
        assertEquals("NOT_FOUND", service(gateway).status(second.id(),
                conversations.history(first.id(), null).messages().getFirst().requestId(), null).state());
        assertThrows(UserProfileStateException.class, () -> service(gateway).history(first.id(), "other-project", null));
    }

    @Test
    void additiveMigrationPreservesExistingFactsAndDoesNotInventConversation() throws Exception {
        String url = "jdbc:sqlite:" + temporary.resolve("legacy.sqlite");
        Flyway.configure().dataSource(url, "", "").target("26").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO user_profile(id,status,revision) VALUES('legacy','EXPLORING',4)");
            statement.executeUpdate("INSERT INTO user_profile_sufficiency(profile_id,revision) VALUES('legacy',4)");
        }
        Flyway.configure().dataSource(url, "", "").target("28").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO discovery_request(profile_id,request_id,kind,fingerprint,state,owner_pid,owner_start,
                        owner_token,expected_revision,result_revision,result_json,created_at)
                    VALUES('legacy','old','QUESTION','fp','COMPLETED',1,'s','t',4,4,
                    '{"sufficient":true,"missingAreas":[],"nextQuestion":"Old question?","profileStatus":"EXPLORING",
                    "assistantReply":"Hello","questionArea":"exploration","deferredAreas":[],"previousQuestionState":"OPEN"}',
                    '2026-10-29T00:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO discovery_message(profile_id,request_id,role,text,question,area,question_state,created_at)
                    VALUES('legacy','old','agent','Hello','Old question?','exploration','ASKED','2026-10-29T00:00:00Z')
                    """);
        }
        assertEquals(1, Flyway.configure().dataSource(url, "", "").load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT revision FROM user_profile WHERE id='legacy'")) {
                assertTrue(result.next());
                assertEquals(4, result.getInt(1));
            }
            try (var result = statement.executeQuery("SELECT sequence,text,question,question_state FROM discovery_message")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertEquals("Hello", result.getString(2));
                assertEquals("Old question?", result.getString(3));
                assertEquals("ASKED", result.getString(4));
                assertFalse(result.next());
            }
            try (var result = statement.executeQuery("SELECT result_json FROM discovery_request")) {
                assertTrue(result.next());
                assertEquals("Old question?", json.readValue(result.getString(1), SufficiencyAssessment.class).nextQuestion());
            }
            try (var result = statement.executeQuery("SELECT sufficient FROM discovery_readiness WHERE profile_id='legacy'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    private DiscoveryConversationService service(AiGateway ai) {
        ProfileSufficiencyEvaluator evaluator = new ProfileSufficiencyEvaluator(ai, json);
        return new DiscoveryConversationService(profiles, conversations, mock(ProjectContextRepository.class),
                new RunUserDiscoveryTurnUseCase(profiles, new ProfileExtraction(ai, json), evaluator),
                new AskDiscoveryQuestionUseCase(profiles, evaluator));
    }

    @Test
    void explicitCancellationPreventsAnUnknownOrLateRequestFromWritingAndCannotUndoSuccess() throws Exception {
        UserProfile profile = seed();
        var service = service(gateway);
        Command unknown = command("TURN", "尚未到达服务器的输入", 1);
        assertEquals("NOT_FOUND", service.status(profile.id(), unknown.requestId(), null).state());
        assertEquals("CANCELLED", service.cancel(profile.id(), unknown.requestId(), null).state());
        assertThrows(UserProfileStateException.class, () -> service.submit(profile.id(), unknown));
        assertTrue(calls.isEmpty());
        Command pending = command("TURN", "正在处理的输入", 1);
        conversations.begin(profile, pending, "fixture");
        assertEquals("CANCELLED", service.cancel(profile.id(), pending.requestId(), null).state());
        var result = new SufficiencyAssessment(
                true, List.of(), null, profile.status(), "已处理");
        assertThrows(UserProfileStateException.class, () -> conversations.complete(profile, profile, pending, result));
        assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
        responses.add(reply(true, "需要整理哪些资料？", "behaviors", "NONE", List.of()));
        Command accepted = command("QUESTION", null, 1);
        service.submit(profile.id(), accepted);
        assertEquals("COMPLETED", service.cancel(profile.id(), accepted.requestId(), null).state());
        assertEquals(1, conversations.history(profile.id(), null).messages().size());
    }

    @Test
    void fullContextKeepsLongReplyAndCorrectsAnOlderRepeatedQuestion() throws Exception {
        UserProfile profile = seed();
        String first = "你目前做过什么类型的开发？";
        var response = json.readTree(reply(false, first, "exploration", "NONE", List.of()));
        ((ObjectNode) response).put("assistantReply", "说明".repeat(1024));
        responses.add(json.writeValueAsString(response));
        service(gateway).submit(profile.id(), command("QUESTION", null, 1));
        var memory = conversations.memory(profile.id());
        assertTrue(memory.history().getLast().text().endsWith(first));
        assertEquals("说明".repeat(1024) + "\n\n" + first, memory.history().getLast().text());
        for (int i = 0; i < 10; i++) {
            responses.add(reply(false, "另一个角度" + UUID.randomUUID() + "？", "exploration", "ANSWERED", List.of()));
            service(gateway).submit(profile.id(), command("TURN", "嗯", 1));
        }
        memory = conversations.memory(profile.id());
        assertEquals(21, memory.history().size());
        assertEquals(11, memory.questions().size());
        assertTrue(memory.questions().contains(first));
        responses.add(reply(false, first, "exploration", "ANSWERED", List.of()));
        String corrected = "哪些步骤最耗时间？";
        responses.add(reply(false, corrected, "behaviors", "ANSWERED", List.of()));
        int before = calls.size();
        assertEquals(corrected, service(gateway).submit(profile.id(), command("TURN", "嗯", 1)).result().nextQuestion());
        assertEquals(before + 2, calls.size());
        assertTrue(calls.getLast().messages().getLast().content().contains("planningCorrection"));
        assertEquals(1, conversations.history(profile.id(), null).messages().stream()
                .filter(message -> first.equals(message.question())).count());
        assertTrue(DiscoveryQuestions.equivalent(
                "你每周能投入多少时间？", "每周你能投入多少时间呢？"));
    }

    @Test
    void historyPagesAndModelContextBothPreserveSequence() throws Exception {
        UserProfile profile = seed();
        for (int i = 0; i < 51; i++) {
            responses.add(reply(true, null, null, "NONE", List.of()));
            service(gateway).submit(profile.id(), command("TURN", "嗯", 1));
        }
        var recent = conversations.history(profile.id(), null);
        assertEquals(100, recent.messages().size());
        assertNotNull(recent.before());
        var older = conversations.history(profile.id(), recent.before());
        assertEquals(2, older.messages().size());
        assertNull(older.before());
        assertTrue(older.messages().getLast().sequence() < recent.messages().getFirst().sequence());
        assertEquals(102, conversations.memory(profile.id()).history().size());
    }

    @Test
    void explicitlyReopenedTopicClearsOnlyItsDeferralAndSyntheticQuestionsCannotReopen() throws Exception {
        UserProfile profile = seed();
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, null, null, "NONE", List.of("constraints", "painPoints")));
        service(gateway).submit(profile.id(), command("TURN", "暂时不聊时间和痛点", 1));
        var proposal = (ObjectNode) json.readTree(
                reply(true, null, null, "NONE", List.of()));
        proposal.putArray("resumedAreas").add("constraints");
        responses.add("{\"hasNewFacts\":false}");
        responses.add(json.writeValueAsString(proposal));
        service(gateway).submit(profile.id(), command("TURN", "现在想讨论一下时间投入", 1));
        assertEquals(List.of("painPoints"), conversations.memory(profile.id()).deferredAreas());
        proposal.put("nextAction", "ASK");
        proposal.put("nextQuestion", "最近哪种阅读操作最不顺手？");
        proposal.put("questionArea", "painPoints");
        proposal.putArray("resumedAreas").add("painPoints");
        responses.add(json.writeValueAsString(proposal));
        assertThrows(AiGatewayException.class, () -> service(gateway).submit(
                profile.id(), command("QUESTION", null, 1)));
        assertEquals(List.of("painPoints"), conversations.memory(profile.id()).deferredAreas());
    }

    @Test
    void firstExplicitNegativeReviewIsCachedWithoutChangingFactsOrStatus() {
        UserProfile profile = seed();
        responses.add("{\"sufficient\":false,\"missingAreas\":[\"具体场景仍不明确\"],\"nextQuestion\":\"先解决什么场景？\"}");
        var review = new AssessProfileSufficiencyUseCase(profiles, new ProfileSufficiencyEvaluator(gateway, json));
        assertFalse(review.assess(profile.id(), 1).sufficient());
        assertFalse(review.assess(profile.id(), 1).sufficient());
        assertEquals(1, calls.size());
        assertEquals(1, profiles.findById(profile.id()).orElseThrow().revision());
        assertEquals(UserProfileStatus.EXPLORING, profiles.findById(profile.id()).orElseThrow().status());
        assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
        assertFalse(profiles.findReadinessAssessment(profile.id(), 1).orElseThrow().sufficient());
    }

    @Test
    void concurrentDifferentReadinessCannotOverwriteAnEstablishedVersionDecision() {
        UserProfile profile = seed();
        var established = new DiscoveryReadiness(
                false, List.of("尚未明确具体场景"));
        profiles.saveWithReadiness(profile, profile, established);
        Command pending = command("TURN", "没有新的事实", 1);
        conversations.begin(profile, pending, "fixture");
        var conflicting = new SufficiencyAssessment(
                true, List.of(), null, profile.status(), "可以审阅");
        assertThrows(UserProfileStateException.class,
                () -> conversations.complete(profile, profile, pending, conflicting));
        assertEquals(established, profiles.findReadinessAssessment(profile.id(), 1).orElseThrow());
        assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
        assertEquals("PENDING", conversations.operation(profile.id(), pending.requestId()).orElseThrow().state());
    }

    @Test
    void acceptedMessagesSurviveClosingAndRestartingTheBackendContext() throws Exception {
        String database = temporary.resolve("backend-restart.sqlite").toString();
        UserProfile profile = seed();
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(false, "最先需要检索哪类笔记？", "behaviors", "NONE", List.of()));
        Command request = command("TURN", "我想检索自己的阅读笔记", 1);
        try (var first = new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE).run(
                "--delveforge.persistence.database-file=" + database, "--spring.main.banner-mode=off")) {
            var storedProfiles = first.getBean(UserProfileRepository.class);
            var storedConversation = first.getBean(DiscoveryConversationRepository.class);
            storedProfiles.save(profile);
            var evaluator = new ProfileSufficiencyEvaluator(gateway, json);
            var service = new DiscoveryConversationService(storedProfiles, storedConversation,
                    mock(ProjectContextRepository.class), new RunUserDiscoveryTurnUseCase(storedProfiles,
                    new ProfileExtraction(gateway, json), evaluator), new AskDiscoveryQuestionUseCase(storedProfiles, evaluator));
            service.submit(profile.id(), request);
        }
        int callsBeforeRestart = calls.size();
        try (var restarted = new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE).run(
                "--delveforge.persistence.database-file=" + database, "--spring.main.banner-mode=off")) {
            var storedConversation = restarted.getBean(DiscoveryConversationRepository.class);
            var history = storedConversation.history(profile.id(), null);
            assertEquals(List.of("user", "agent"), history.messages().stream()
                    .map(DiscoveryConversationRepository.Message::role).toList());
            assertEquals("我想检索自己的阅读笔记", history.messages().getFirst().text());
            assertEquals("最先需要检索哪类笔记？", history.unansweredQuestion());
            assertEquals("COMPLETED", storedConversation.operation(profile.id(), request.requestId()).orElseThrow().state());
            assertEquals(callsBeforeRestart, calls.size());
        }
    }

    @Test
    void projectAssociationIsRetainedAndRecheckedWhenThePendingResultCommits() throws Exception {
        UserProfile profile = seed();
        String projectId = "project-" + UUID.randomUUID();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO project_flow_context(id,name,user_profile_id,asset_ids,repository_profile_ids,
                    direction_ids,created_at,updated_at,version,flow_id) VALUES(?,?,?,'[]','[]','[]',?,?,0,?)
                """, projectId, "合成需求项目", profile.id().value(), "2026-10-29T00:00:00Z", "2026-10-29T00:00:00Z", projectId);
        Command accepted = new Command(UUID.randomUUID().toString(), "QUESTION", null, 1, projectId);
        conversations.begin(profile, accepted, "accepted");
        var result = new SufficiencyAssessment(
                true, List.of(), "希望先整理什么内容？", profile.status(), "从日常开始。", "behaviors", List.of());
        conversations.complete(profile, profile, accepted, result);
        assertEquals(projectId, conversations.history(profile.id(), null).messages().getFirst().projectId());
        Command stale = new Command(UUID.randomUUID().toString(), "TURN", "继续", 1, projectId);
        conversations.begin(profile, stale, "stale");
        jdbc.update("UPDATE project_flow_context SET user_profile_id=NULL WHERE id=?", projectId);
        assertThrows(UserProfileStateException.class, () -> conversations.complete(profile, profile, stale, result));
        assertEquals(1, conversations.history(profile.id(), null).messages().size());
        assertEquals("PENDING", conversations.operation(profile.id(), stale.requestId()).orElseThrow().state());
    }

    @Test
    void bothStagesReceiveCompleteStableHistoryAfterManyExplanations() throws Exception {
        UserProfile profile = seed();
        String question = "你最先希望检索哪一种阅读笔记？";
        Command first = command("QUESTION", null, 1);
        conversations.begin(profile, first, "first");
        conversations.complete(profile, profile, first, new SufficiencyAssessment(
                true, List.of(), question, profile.status(), "从一个具体场景想起。", "behaviors", List.of()));
        for (int index = 0; index < 6; index++) {
            Command explanation = command("TURN", "能再解释一下吗", 1);
            conversations.begin(profile, explanation, "explanation-" + index);
            conversations.complete(profile, profile, explanation,
                    new SufficiencyAssessment(true, List.of(), null, profile.status(), "可以从日常操作想起。"));
        }
        var memory = conversations.memory(profile.id());
        assertEquals(13, memory.history().size());
        assertTrue(memory.history().getFirst().text().endsWith(question));
        assertEquals(question, memory.unansweredQuestion());
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, null, null, "ANSWERED", List.of()));
        service(gateway).submit(profile.id(), command("TURN", "就刚才那一种，自己的阅读笔记", 1));
        assertEquals(memory.history(), conversations.memory(profile.id()).history().subList(0, 13));
        for (var request : calls) {
            assertEquals(memory.history().stream().map(m -> m.text()).toList(),
                    request.messages().subList(1, 14).stream().map(m -> m.content()).toList());
            assertEquals(List.of(1, 2, 4, 6, 8, 10, 12), request.discoveryTurnStarts());
        }
    }

    @Test
    void replacementFollowsUnknownOrChangedDirectionWithoutDeferringTheTopic() throws Exception {
        UserProfile profile = seed();
        String broad = "什么项目最适合你？";
        responses.add(reply(false, broad, "projectGoals", "NONE", List.of()));
        service(gateway).submit(profile.id(), command("QUESTION", null, 1));
        for (String input : List.of("我不知道，可以举个例子吗？", "其实我更想做一个游戏")) {
            String next = input.startsWith("我不知道") ? "上周你用过哪款小工具？" : "你想先做哪种游戏体验？";
            responses.add("{\"hasNewFacts\":false}");
            responses.add(reply(false, next, "interests", "REPLACED", List.of()));
            service(gateway).submit(profile.id(), command("TURN", input, 1));
            assertEquals(next, conversations.history(profile.id(), null).unansweredQuestion());
            assertTrue(conversations.memory(profile.id()).deferredAreas().isEmpty());
        }
        assertEquals(List.of("REPLACED", "REPLACED", "ASKED"), conversations.history(profile.id(), null).messages()
                .stream().filter(m -> m.question() != null).map(m -> m.questionState()).toList());
        int before = calls.size();
        assertThrows(UserProfileStateException.class, () -> service(gateway).submit(profile.id(), command("QUESTION", null, 1)));
        assertEquals(before, calls.size());
    }

    @Test
    void invalidCorrectionLeavesPendingQuestionProfileEvidenceAndHistoryUntouched() throws Exception {
        UserProfile profile = seed();
        responses.add(reply(false, "你打算先做什么？", "projectGoals", "NONE", List.of()));
        service(gateway).submit(profile.id(), command("QUESTION", null, 1));
        var history = conversations.history(profile.id(), null);
        responses.add("{\"hasNewFacts\":true,\"constraints\":[\"一小时\"],\"replacements\":{\"constraints\":{\"每周五小时\":\"一小时\"}},\"evidenceClaims\":[\"可用一小时\"]}");
        responses.add(reply(false, "你打算先做什么？", "projectGoals", "ANSWERED", List.of()));
        responses.add(reply(false, "另一个问题？", "behaviors", "ASKED", List.of()));
        Command invalid = command("TURN", "我只有一小时，想先试做阅读笔记", 1);
        assertThrows(AiGatewayException.class, () -> service(gateway).submit(profile.id(), invalid));
        assertEquals("FAILED", conversations.operation(profile.id(), invalid.requestId()).orElseThrow().state());
        assertEquals(history, conversations.history(profile.id(), null));
        assertEquals(profile.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
        assertEquals(1, profiles.findById(profile.id()).orElseThrow().revision());
        assertEquals(4, calls.size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void invitationCanBeContinuedByButtonOrFreeInputAndDoesNotRepeat(boolean byButton) throws Exception {
        UserProfile profile = seed();
        Command first = command("TURN", "嗯", 1);
        responses.add(reply(true, null, null, "NONE", List.of()));
        var invite = service(gateway).submit(profile.id(), first);
        assertTrue(invite.result().reviewInvitation());
        assertEquals(first.requestId(), conversations.history(profile.id(), null).invitationRequestId());
        Command follow = byButton ? command("QUESTION", null, 1) : command("TURN", "我还想整理读后感", 1);
        if (!byButton) {
            responses.add("{\"hasNewFacts\":false}");
        }
        responses.add(reply(true, "想怎么查找以前的笔记？", "behaviors", "NONE", List.of()));
        assertFalse(service(gateway).submit(profile.id(), follow).result().reviewInvitation());
        var restarted = new SqliteDiscoveryConversationRepository(source, manager, profiles, json);
        assertNull(restarted.history(profile.id(), null).invitationRequestId());
        assertTrue(restarted.memory(profile.id()).invitationPresented());
        responses.add("{\"hasNewFacts\":true,\"projectGoals\":[\"给笔记做索引\"],\"replacements\":{\"projectGoals\":{\"六周做个人阅读笔记接口展示后端能力\":\"给笔记做索引\"}},\"evidenceClaims\":[\"用户需要索引\"]}");
        responses.add(reply(true, "搜索时最常记得什么线索？", "behaviors", "ANSWERED", List.of()));
        var later = service(gateway).submit(profile.id(), command("TURN", "需要给笔记做索引", 1));
        assertTrue(later.revision() > 1);
        assertFalse(later.result().reviewInvitation());
        assertNull(restarted.history(profile.id(), null).invitationRequestId());
        assertEquals(UserProfileStatus.EXPLORING, profiles.findById(profile.id()).orElseThrow().status());
    }

    @Test
    void substantiveChangeClearsPositiveReadinessAndTheActiveInvitation() throws Exception {
        UserProfile profile = seed();
        responses.add(reply(true, null, null, "NONE", List.of()));
        Command initial = command("TURN", "嗯", 1);
        service(gateway).submit(profile.id(), initial);
        assertEquals(initial.requestId(), conversations.history(profile.id(), null).invitationRequestId());
        responses.add("""
                {"hasNewFacts":true,"projectGoals":["想换方向"],"replacements":{"projectGoals":{"六周做个人阅读笔记接口展示后端能力":"想换方向"}},"evidenceClaims":["用户改变目标"]}
                """);
        responses.add(reply(false, "新方向中你最想尝试什么？", "projectGoals", "NONE", List.of()));
        var changed = service(gateway).submit(profile.id(), command("TURN", "我想换方向，具体目标还没有想好", 1));
        assertTrue(changed.revision() > 1);
        assertFalse(conversations.readiness(profile.id(), changed.revision()).orElseThrow().sufficient());
        assertNull(conversations.history(profile.id(), null).invitationRequestId());
        assertTrue(conversations.memory(profile.id()).invitationPresented());
    }

    @Test
    void bothStageRequestsTrimChatLocallyWhileSqlHistoryStaysComplete() throws Exception {
        var profile = seed();
        for (int index = 0; index < 15; index++) {
            var turn = command("TURN", "第" + index + "轮：" + "阅读笔记".repeat(500), 1);
            conversations.begin(profile, turn, "seed-" + index);
            conversations.complete(profile, profile, turn, new SufficiencyAssessment(false,
                    List.of("具体使用场景"), null, profile.status(), "可以再描述一下使用场景。"));
        }
        var accepted = conversations.memory(profile.id()).history();
        var client = RestClient.builder().baseUrl("http://fixture.invalid");
        var server = MockRestServiceServer.bindTo(client).build();
        for (String response : List.of("{\"hasNewFacts\":false}",
                reply(false, "最近想找回哪一条笔记？", "behaviors", "NONE", List.of()))) {
            server.expect(request -> {
                var bytes = ((MockClientHttpRequest) request).getBodyAsBytes();
                assertTrue(bytes.length <= 18000);
                var messages = json.readTree(bytes).get("messages");
                assertTrue(messages.size() > 2 && messages.size() < accepted.size() + 2);
                assertEquals(0, messages.size() % 2);
                var suffix = accepted.subList(accepted.size() + 2 - messages.size(), accepted.size());
                for (int index = 0; index < suffix.size(); index++) {
                    assertEquals(suffix.get(index).text(), messages.get(index + 1).get("content").textValue());
                }
                assertTrue(messages.get(messages.size() - 1).get("content").textValue().contains("想试试检索"));
            }).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                    json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                            "message", Map.of("content", response))))), MediaType.APPLICATION_JSON));
        }
        var settings = new DeepSeekProperties("http://fixture.invalid", "fixture", "deepseek-flash",
                Duration.ofSeconds(5), false, Duration.ofSeconds(10), 16384, 8388608, 65536, 18000);
        try (var counter = new DeepSeekTokenCounterAdapter("deepseek-flash", AiRequest.Thinking.HIGH)) {
            var adapter = new DeepSeekAiGatewayAdapter(client.build(), json, settings, () -> counter);
            service(adapter).submit(profile.id(), command("TURN", "想试试检索", 1));
        }
        server.verify();
        assertEquals(32, conversations.history(profile.id(), null).messages().size());
        assertEquals(accepted, conversations.memory(profile.id()).history().subList(0, 30));
    }

    @Test
    void pausingDoesNotAnswerOrDeferAnUnresolvedQuestion() throws Exception {
        var profile = seed();
        String question = "先从哪个阅读场景开始？";
        responses.add(reply(false, question, "behaviors", "NONE", List.of()));
        service(gateway).submit(profile.id(), command("QUESTION", null, 1));
        var pause = (ObjectNode) json.readTree(reply(false, null, null, "ASKED", List.of()));
        pause.put("nextAction", "PAUSE");
        pause.put("assistantReply", "好，等你想继续时再聊。");
        responses.add("{\"hasNewFacts\":false}");
        responses.add(json.writeValueAsString(pause));
        service(gateway).submit(profile.id(), command("TURN", "我想先暂停", 1));
        var history = conversations.history(profile.id(), null);
        assertEquals(question, history.unansweredQuestion());
        assertEquals("ASKED", history.messages().getFirst().questionState());
        assertEquals("PAUSE", history.messages().getLast().nextAction().name());
        assertNull(history.messages().getLast().question());
        assertTrue(conversations.memory(profile.id()).deferredAreas().isEmpty());
    }

    @Test
    void reviewCorrectionsSurviveReopeningAndStaleProposalsButCanBeExplicitlyChanged() throws Exception {
        var id = seed().id();
        var service = service(gateway);
        responses.add("""
                {"hasNewFacts":true,"constraints":["一个晚上完成"],"replacements":{"constraints":{"每周五小时":"一个晚上完成"}},"evidenceClaims":["用户希望一个晚上完成"]}
                """);
        responses.add(reply(true, null, null, "NONE", List.of()));
        var original = service.submit(id, command("TURN", "我想一个晚上完成", 1));
        new AssessProfileSufficiencyUseCase(profiles, new ProfileSufficiencyEvaluator(gateway, json))
                .assess(id, original.revision());
        var corrected = new UpdateUserProfileUseCase(profiles).update(new UpdateUserProfileRequest(id,
                List.of("摄影"), null, null, null, null, List.of("约两小时完成"), null, original.revision()));
        new ConfirmUserProfileUseCase(profiles).confirm(id, corrected.revision());
        new ReopenDiscoveryUseCase(profiles).reopenDiscovery(id);
        var revision = profiles.findById(id).orElseThrow().revision();
        var accepted = conversations.history(id, null);
        assertTrue(corrected.evidence().stream().anyMatch(e -> e.sourceRef().contains("一个晚上")));
        String input = "在 README 加一段使用示例，其他边界保持不变";
        for (var stale : List.of(Map.of("constraints", List.of("一个晚上完成")),
                Map.of("interests", List.of("阅读")), Map.of("constraints", List.of("约两小时完成", "一个晚上完成")),
                Map.of("constraints", List.of("使用示例")))) {
            var proposal = json.valueToTree(stale);
            var object = (ObjectNode) proposal;
            object.put("hasNewFacts", true);
            object.putArray("behaviors").add("补充示例");
            object.putArray("evidenceClaims").add("需要 README 示例");
            object.putArray("retractedEvidenceIndices").add(0);
            if (stale.getOrDefault("constraints", List.of()).contains("一个晚上完成")) {
                object.putObject("replacements").putObject("constraints").put("约两小时完成", "一个晚上完成");
            }
            responses.add(json.writeValueAsString(object));
            int before = calls.size();
            var command = command("TURN", input, revision);
            assertThrows(AiGatewayException.class, () -> service.submit(id, command));
            assertEquals(before + 1, calls.size());
            assertEquals("FAILED", service.status(id, command.requestId(), null).state());
            assertEquals(accepted, conversations.history(id, null));
            var reloaded = profiles.findById(id).orElseThrow();
            assertEquals(revision, reloaded.revision());
            assertEquals(corrected.constraints(), reloaded.constraints());
            assertEquals(corrected.interests(), reloaded.interests());
            assertEquals(corrected.evidence(), reloaded.evidence());
        }
        responses.add("""
                {"hasNewFacts":true,"behaviors":["补充 README 使用示例"],"evidenceClaims":["需要 README 示例"]}
                """);
        responses.add(reply(true, null, null, "NONE", List.of()));
        var continued = service.submit(id, command("TURN", input, revision));
        assertEquals(List.of("约两小时完成"), profiles.findById(id).orElseThrow().constraints());
        responses.add("""
                {"hasNewFacts":true,"constraints":["三小时完成"],
                 "replacements":{"constraints":{"约两小时完成":"三小时完成"}},
                 "evidenceClaims":["用户明确修改可用时间"]}
                """);
        responses.add(reply(true, null, null, "NONE", List.of()));
        service.submit(id, command("TURN", "现在改成三小时完成", continued.revision()));
        assertEquals(List.of("三小时完成"), profiles.findById(id).orElseThrow().constraints());
        assertEquals(List.of("约两小时完成"), profiles.findSections(id, revision).orElseThrow().constraints());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void repeatedReplacementQuestionGetsOnePlanningCorrection(boolean validCorrection) throws Exception {
        var profile = seed();
        var service = service(gateway);
        responses.add(reply(false, "想尝试哪种后端项目？", "projectGoals", "NONE", List.of()));
        service.submit(profile.id(), command("QUESTION", null, 1));
        var history = conversations.history(profile.id(), null);
        String replacement = "上面几类里，哪个最接近你愿意动手做的？如果没有，也可以说说你平时想用程序解决的一个小场景。";
        var duplicated = (ObjectNode) json.readTree(reply(false, replacement, "behaviors", "REPLACED", List.of()));
        duplicated.put("assistantReply", "你更愿意做哪一类？或者你生活/学习中有没有想用程序解决的小场景？");
        String invalid = json.writeValueAsString(duplicated);
        responses.add("{\"hasNewFacts\":false}");
        responses.add(invalid);
        duplicated.put("assistantReply", "为什么从想用程序解决的小场景说起？熟悉的事情更容易举例，例如整理课堂笔记。");
        responses.add(validCorrection ? json.writeValueAsString(duplicated) : invalid);
        var turn = command("TURN", "我不知道怎么回答这个问题。", 1);
        if (validCorrection) {
            var result = service.submit(profile.id(), turn).result();
            assertEquals(replacement, result.nextQuestion());
            assertEquals(duplicated.get("assistantReply").textValue(), result.assistantReply());
            var saved = conversations.history(profile.id(), null);
            assertEquals("REPLACED", saved.messages().getFirst().questionState());
            assertEquals("ASKED", saved.messages().getLast().questionState());
            assertEquals(result.assistantReply(), saved.messages().getLast().text());
            assertEquals(replacement, saved.messages().getLast().question());
            assertTrue(conversations.memory(profile.id()).deferredAreas().isEmpty());
        } else {
            assertThrows(AiGatewayException.class, () -> service.submit(profile.id(), turn));
            assertEquals(history, conversations.history(profile.id(), null));
            assertEquals("FAILED", service.status(profile.id(), turn.requestId(), null).state());
            assertEquals(1, profiles.findById(profile.id()).orElseThrow().revision());
        }
        assertEquals(4, calls.size());
        assertTrue(calls.getLast().messages().getLast().content().contains("正文与 nextQuestion 重复提问"));
    }

    private UserProfile seed() {
        UserProfile profile = UserProfile.reconstitute(new UserProfileId("chat-" + UUID.randomUUID()),
                UserProfileStatus.EXPLORING, 1, List.of("阅读"), List.of(), List.of(), List.of("Java REST接口经验"),
                List.of("六周做个人阅读笔记接口展示后端能力"), List.of("每周五小时"),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "我会Java，六周做阅读笔记接口，每周五小时",
                        "用户说明目标、能力及投入范围", null, false)));
        profiles.save(profile);
        return profile;
    }

    private Command command(String kind, String input, int revision) {
        return new Command(UUID.randomUUID().toString(), kind, input, revision, null);
    }

    private String reply(boolean sufficient, String question, String area, String previous, List<String> deferred)
            throws Exception {
        return json.writeValueAsString(Map.of("sufficient", sufficient,
                "missingAreas", sufficient ? List.of() : List.of("具体使用场景"), "assistantReply", "可以继续从实际需求聊起。",
                "nextQuestion", question == null ? "" : question, "questionArea", area == null ? "" : area,
                "nextAction", question != null ? "ASK" : "ASKED".equals(previous) ? "KEEP_PENDING" : "PAUSE",
                "deferredAreas", deferred, "previousQuestionState", previous));
    }
}
