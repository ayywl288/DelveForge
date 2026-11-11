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
        var omitted = service(gateway).submit(profile.id(), command("QUESTION", null, profile.revision()));
        assertNull(omitted.result().nextQuestion());
        assertEquals("PAUSE", omitted.result().nextAction().name());
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
        assertFalse(service(gateway).submit(profile.id(), command("TURN", "暂时没有更多想法", 1)).result().sufficient());
        int callsBeforeReview = calls.size();
        assertFalse(new AssessProfileSufficiencyUseCase(profiles, new ProfileSufficiencyEvaluator(gateway, json))
                .assess(profile.id(), 1).sufficient());
        assertEquals(callsBeforeReview, calls.size());
        responses.add("{\"hasNewFacts\":true,\"projectGoals\":[\"六周做个人阅读笔记接口展示后端能力\",\"给阅读笔记做全文检索\"],\"replacements\":{\"projectGoals\":{\"六周做个人阅读笔记接口展示后端能力\":\"给阅读笔记做全文检索\"}},\"evidenceClaims\":[\"用户明确阅读检索目标\"]}");
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
        assertEquals(2, Flyway.configure().dataSource(url, "", "").load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM user_profile_section_protection WHERE profile_id='legacy'")) {
                assertTrue(result.next());
                assertEquals(6, result.getInt(1));
            }
            statement.executeUpdate("INSERT INTO user_profile(id,status,revision) VALUES('new-after','EXPLORING',1)");
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM user_profile_section_protection WHERE profile_id='new-after'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
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
    void fullContextKeepsLongReplyAndDropsAnOlderRepeatedOptionalQuestionWithoutAnotherCall() throws Exception {
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
        int before = calls.size();
        var result = service(gateway).submit(profile.id(), command("TURN", "嗯", 1));
        assertNull(result.result().nextQuestion());
        assertEquals("可以继续从实际需求聊起。", result.result().assistantReply());
        assertEquals(before + 1, calls.size());
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
        var omitted = service(gateway).submit(profile.id(), command("QUESTION", null, 1));
        assertNull(omitted.result().nextQuestion());
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
        responses.add("{\"sufficient\":false,\"assistantReply\":99}");
        Command invalid = command("TURN", "我只有一小时，想先试做阅读笔记", 1);
        assertThrows(AiGatewayException.class, () -> service(gateway).submit(profile.id(), invalid));
        assertEquals("FAILED", conversations.operation(profile.id(), invalid.requestId()).orElseThrow().state());
        assertEquals(history, conversations.history(profile.id(), null));
        assertEquals(profile.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
        assertEquals(1, profiles.findById(profile.id()).orElseThrow().revision());
        assertEquals(3, calls.size());
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
        responses.add("{\"hasNewFacts\":true,\"projectGoals\":[\"六周做个人阅读笔记接口展示后端能力\",\"给笔记做索引\"],\"replacements\":{\"projectGoals\":{\"六周做个人阅读笔记接口展示后端能力\":\"给笔记做索引\"}},\"evidenceClaims\":[\"用户需要索引\"]}");
        responses.add(reply(true, "搜索时最常记得什么线索？", "behaviors", "ANSWERED", List.of()));
        var later = service(gateway).submit(profile.id(), command("TURN", "需要给笔记做索引", 1));
        assertTrue(later.revision() > 1);
        assertFalse(later.result().reviewInvitation());
        assertNull(restarted.history(profile.id(), null).invitationRequestId());
        assertEquals(UserProfileStatus.EXPLORING, profiles.findById(profile.id()).orElseThrow().status());
    }

    @Test
    void pendingChangeKeepsAcceptedReadinessUntilApprovalThenInvalidatesIt() throws Exception {
        UserProfile profile = seed();
        responses.add(reply(true, null, null, "NONE", List.of()));
        Command initial = command("TURN", "嗯", 1);
        service(gateway).submit(profile.id(), initial);
        assertEquals(initial.requestId(), conversations.history(profile.id(), null).invitationRequestId());
        responses.add("""
                {"hasNewFacts":true,"projectGoals":["想换方向"],"evidenceClaims":["用户改变目标"]}
                """);
        responses.add(reply(true, null, null, "NONE", List.of()));
        var service = service(gateway);
        var staged = service.submit(profile.id(), command("TURN", "我想换方向，具体目标还没有想好", 1));
        assertEquals(1, staged.revision());
        assertTrue(conversations.readiness(profile.id(), 1).orElseThrow().sufficient());
        assertFalse(staged.result().reviewInvitation());
        assertNull(conversations.history(profile.id(), null).invitationRequestId());
        var accepted = service.resolveProfileUpdate(profile.id(), staged.proposal().id(), true, null);
        assertTrue(accepted.resultingRevision() > 1);
        assertTrue(conversations.readiness(profile.id(), accepted.resultingRevision()).isEmpty());
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
        UserProfile original = seed();
        original.updateConstraints(List.of("一个晚上完成"));
        original.recordEvidence(new Evidence(EvidenceSourceType.USER_INPUT, "希望一个晚上完成", "旧的时间说明", null, false));
        profiles.save(original);
        var id = original.id();
        var service = service(gateway);
        profiles.recordSufficientAssessment(original);
        new AssessProfileSufficiencyUseCase(profiles, new ProfileSufficiencyEvaluator(gateway, json))
                .assess(id, original.revision());
        var corrected = new UpdateUserProfileUseCase(profiles).update(new UpdateUserProfileRequest(id,
                List.of("摄影"), null, null, null, null, List.of("约两小时完成"), null, original.revision()));
        new ConfirmUserProfileUseCase(profiles).confirm(id, corrected.revision());
        new ReopenDiscoveryUseCase(profiles).reopenDiscovery(id);
        int revision = corrected.revision();
        assertEquals(6, profiles.protectedSections(id).size());
        assertTrue(corrected.evidence().stream().anyMatch(e -> e.sourceRef().contains("一个晚上")));
        String input = "在 README 加一段使用示例，其他边界保持不变";
        for (var stale : List.of(Map.of("constraints", List.of("一个晚上完成")),
                Map.of("interests", List.of("阅读")), Map.of("constraints", List.of("约两小时完成", "一个晚上完成")),
                Map.of("constraints", List.of("使用示例")))) {
            var object = (ObjectNode) json.valueToTree(stale);
            object.put("hasNewFacts", true);
            object.putArray("behaviors").add("补充示例");
            object.putArray("evidenceClaims").add("需要 README 示例");
            object.putArray("retractedEvidenceIndices").add(0);
            object.putObject("replacements").putObject("constraints").put("约两小时完成", "一个晚上完成");
            responses.add(json.writeValueAsString(object));
            responses.add(reply(true, null, null, "NONE", List.of()));
            int before = calls.size();
            Command command = command("TURN", input, revision);
            var result = service.submit(id, command);
            assertEquals(before + 2, calls.size());
            assertEquals("PENDING", result.proposal().state());
            assertEquals(result, service.submit(id, command));
            assertEquals(before + 2, calls.size());
            assertEquals(result.proposal(), new SqliteDiscoveryConversationRepository(source, manager, profiles, json)
                    .history(id, null).pendingProposal());
            assertThrows(UserProfileStateException.class,
                    () -> service.submit(id, command("TURN", "另一条新消息", revision)));
            var reloaded = profiles.findById(id).orElseThrow();
            assertEquals(revision, reloaded.revision());
            assertEquals(corrected.constraints(), reloaded.constraints());
            assertEquals(corrected.interests(), reloaded.interests());
            assertEquals(corrected.behaviors(), reloaded.behaviors());
            assertEquals(corrected.evidence(), reloaded.evidence());
            assertEquals("DISMISSED", service.resolveProfileUpdate(id, result.proposal().id(), false, null).state());
            assertEquals("DISMISSED", service.resolveProfileUpdate(id, result.proposal().id(), false, null).state());
            assertNull(conversations.history(id, null).pendingProposal());
        }
        responses.add("""
                {"hasNewFacts":true,"constraints":["约三小时完成"],"evidenceClaims":["用户明确修改可用时间为三小时"]}
                """);
        responses.add(reply(true, null, null, "NONE", List.of()));
        var staged = service.submit(id, command("TURN", "现在改成三小时完成", revision));
        var accepted = service.resolveProfileUpdate(id, staged.proposal().id(), true, null);
        assertEquals("ACCEPTED", accepted.state());
        assertEquals(List.of("约三小时完成"), profiles.findById(id).orElseThrow().constraints());
        assertEquals(corrected.evidence().size() + 1, profiles.findById(id).orElseThrow().evidence().size());
        assertFalse(profiles.findById(id).orElseThrow().evidence().getLast().confirmed());
        assertEquals(accepted, service.resolveProfileUpdate(id, staged.proposal().id(), true, null));
        assertEquals(accepted.resultingRevision(), profiles.findById(id).orElseThrow().revision());
        assertEquals(List.of("约两小时完成"), profiles.findSections(id, revision).orElseThrow().constraints());
    }

    @Test
    void semanticallySimilarReplyAndFollowUpAreAcceptedWithoutAPlanningRetry() throws Exception {
        var profile = seed();
        var service = service(gateway);
        responses.add(reply(false, "想尝试哪种后端项目？", "projectGoals", "NONE", List.of()));
        service.submit(profile.id(), command("QUESTION", null, 1));
        String question = "上面几类里，哪个最接近你愿意动手做的？如果没有，也可以说说你平时想用程序解决的一个小场景。";
        var planned = (ObjectNode) json.readTree(reply(false, question, "behaviors", "REPLACED", List.of()));
        planned.put("assistantReply", "你更愿意做哪一类？或者你生活/学习中有没有想用程序解决的小场景？");
        responses.add("{\"hasNewFacts\":false}");
        responses.add(json.writeValueAsString(planned));
        var result = service.submit(profile.id(), command("TURN", "我不知道怎么回答这个问题。", 1)).result();
        assertEquals(3, calls.size());
        assertEquals(planned.get("assistantReply").textValue(), result.assistantReply());
        assertEquals(question, result.nextQuestion());
        var saved = conversations.history(profile.id(), null);
        assertEquals("REPLACED", saved.messages().getFirst().questionState());
        assertEquals("ASKED", saved.messages().getLast().questionState());
        assertEquals(1, profiles.findById(profile.id()).orElseThrow().revision());
        assertTrue(conversations.memory(profile.id()).deferredAreas().isEmpty());
    }

    @Test
    void exactAppendAcceptsParaphrasedFactsAndNoEffectiveChangeAddsNoEvidence() throws Exception {
        UserProfile profile = skillsProfile();
        responses.add("""
                {"hasNewFacts":true,"technicalCapabilities":["Java","Spring Boot","Redis缓存实践"],
                 "evidenceClaims":["用户最近练习了 Redis 缓存"]}
                """);
        responses.add(reply(false, "打算在哪个场景使用缓存？", "technicalCapabilities", "NONE", List.of()));
        var first = service(gateway).submit(profile.id(), command("TURN", "最近学了Redis，试过给查询加缓存", 1));
        assertNull(first.proposal());
        assertEquals(List.of("Java", "Spring Boot", "Redis缓存实践"), profiles.findById(profile.id()).orElseThrow().technicalCapabilities());
        assertEquals(3, first.revision());
        assertFalse(calls.getFirst().messages().getLast().content().contains("groundedSections"));
        var accepted = profiles.findById(profile.id()).orElseThrow();
        responses.add("""
                {"hasNewFacts":true,"technicalCapabilities":["Java","Spring Boot","Redis缓存实践"],
                 "evidenceClaims":["无实际变化时不追加这条判断"],"retractedEvidenceIndices":[0]}
                """);
        responses.add(reply(false, null, null, "ASKED", List.of()));
        var unchanged = service(gateway).submit(profile.id(), command("TURN", "还是这些经验", first.revision()));
        assertEquals(accepted.revision(), unchanged.revision());
        assertEquals(accepted.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
    }

    @Test
    void rewriteReorderAndDuplicateAppendRequireWholeTurnApproval() throws Exception {
        for (List<String> values : List.of(List.of("Spring Boot", "Java"), List.of("Java后端", "Spring Boot"),
                List.of("Java", "Spring Boot", "Java"))) {
            UserProfile profile = skillsProfile();
            responses.add(json.writeValueAsString(Map.of("hasNewFacts", true, "technicalCapabilities", values,
                    "behaviors", List.of("每晚整理笔记"), "evidenceClaims", List.of("本轮描述的技术与笔记习惯"))));
            responses.add(reply(false, null, null, "NONE", List.of()));
            var staged = service(gateway).submit(profile.id(), command("TURN", "我还会这样整理笔记", 1));
            var current = profiles.findById(profile.id()).orElseThrow();
            assertEquals(1, current.revision());
            assertEquals(profile.technicalCapabilities(), current.technicalCapabilities());
            assertEquals(profile.behaviors(), current.behaviors());
            assertEquals(profile.evidence(), current.evidence());
            assertEquals(2, staged.proposal().proposedSections().size());
            assertFalse(staged.result().reviewInvitation());
            assertEquals(com.ayywl.delveforge.application.userdiscovery.sufficiency.DialogueAction.PAUSE, staged.result().nextAction());
            assertTrue(calls.getLast().messages().getLast().content().contains("\"pendingProfileUpdate\":true"));
            assertFalse(calls.getLast().messages().getLast().content().contains("每晚整理笔记\"]"));
        }
    }

    @Test
    void approvalPausePreservesUnresolvedQuestionAndDoesNotInvokePlannerAfterResolution() throws Exception {
        var profile = seed();
        var service = service(gateway);
        responses.add(reply(false, "最近哪次找笔记最费时间？", "behaviors", "NONE", List.of()));
        service.submit(profile.id(), command("QUESTION", null, 1));
        responses.add("""
                {"hasNewFacts":true,"constraints":["每周三小时"],"evidenceClaims":["用户纠正投入时间"]}
                """);
        var pause = (ObjectNode) json.readTree(reply(false, null, null, "ASKED", List.of()));
        pause.put("nextAction", "PAUSE");
        responses.add(json.writeValueAsString(pause));
        var staged = service.submit(profile.id(), command("TURN", "先改一下时间，每周三小时", 1));
        String question = conversations.history(profile.id(), null).unansweredQuestion();
        assertEquals("最近哪次找笔记最费时间？", question);
        assertEquals("ASKED", conversations.history(profile.id(), null).messages().getFirst().questionState());
        assertNull(conversations.history(profile.id(), null).messages().getLast().questionState());
        assertEquals(List.of(), conversations.memory(profile.id()).deferredAreas());
        int before = calls.size();
        var accepted = service.resolveProfileUpdate(profile.id(), staged.proposal().id(), true, null);
        assertEquals(before, calls.size());
        assertEquals(accepted, service.resolveProfileUpdate(profile.id(), staged.proposal().id(), true, null));
        assertEquals(question, conversations.history(profile.id(), null).unansweredQuestion());
        assertEquals(1, new JdbcTemplate(source).queryForObject(
                "SELECT COUNT(*) FROM discovery_message WHERE profile_id=? AND question_state='ASKED'", Integer.class, profile.id().value()));
    }

    @Test
    void manualNoOpAndConcurrentRevisionChangeSupersedePendingWithoutLosingConversation() throws Exception {
        for (boolean noOp : List.of(true, false)) {
            var profile = seed();
            responses.add("{\"hasNewFacts\":true,\"constraints\":[\"每周三小时\"],\"evidenceClaims\":[\"三小时\"]}");
            responses.add(reply(false, null, null, "NONE", List.of()));
            var service = service(gateway);
            var staged = service.submit(profile.id(), command("TURN", "改成三小时", 1));
            var history = conversations.memory(profile.id()).history();
            var edited = new UpdateUserProfileUseCase(profiles).update(new UpdateUserProfileRequest(profile.id(),
                    null, null, null, null, null, noOp ? profile.constraints() : List.of("每周六小时"), null, 1));
            assertEquals(noOp ? 1 : 2, edited.revision());
            assertEquals("SUPERSEDED", service.resolveProfileUpdate(profile.id(), staged.proposal().id(), true, null).state());
            assertNull(conversations.history(profile.id(), null).pendingProposal());
            assertEquals(edited.constraints(), profiles.findById(profile.id()).orElseThrow().constraints());
            assertEquals(history, conversations.memory(profile.id()).history());
            assertTrue(profiles.protectedSections(profile.id()).contains("constraints"));
        }
    }

    @Test
    void noOpHumanProtectionDuringModelRequestRejectsLateAutomaticAppend() throws Exception {
        UserProfile profile = skillsProfile();
        AiGateway delayed = request -> {
            if (request.thinking() == AiRequest.Thinking.DISABLED) {
                new UpdateUserProfileUseCase(profiles).update(new UpdateUserProfileRequest(profile.id(), null, null,
                        null, profile.technicalCapabilities(), null, null, null, 1));
                return "{\"hasNewFacts\":true,\"technicalCapabilities\":[\"Java\",\"Spring Boot\",\"Redis\"],\"evidenceClaims\":[\"会Redis\"]}";
            }
            return replyUnchecked(false);
        };
        var command = command("TURN", "最近学了Redis", 1);
        assertThrows(UserProfileStateException.class, () -> service(delayed).submit(profile.id(), command));
        assertEquals(profile.technicalCapabilities(), profiles.findById(profile.id()).orElseThrow().technicalCapabilities());
        assertEquals(profile.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
        assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
        assertEquals("FAILED", conversations.operation(profile.id(), command.requestId()).orElseThrow().state());
    }

    @Test
    void failedApprovalRollsBackProposalProfileEvidenceProtectionAndReadiness() throws Exception {
        var profile = skillsProfile();
        var service = service(gateway);
        responses.add("""
                {"hasNewFacts":true,"technicalCapabilities":["Spring Boot","Java"],
                 "constraints":["每周三小时"],"evidenceClaims":["本轮纠正技术与时间"]}
                """);
        responses.add(reply(false, null, null, "NONE", List.of()));
        var staged = service.submit(profile.id(), command("TURN", "调整技术顺序，时间改三小时", 1));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TRIGGER fail_profile_protection BEFORE INSERT ON user_profile_section_protection
                BEGIN SELECT RAISE(ABORT, 'injected approval failure'); END
                """);
        try {
            assertThrows(RuntimeException.class, () -> service.resolveProfileUpdate(profile.id(), staged.proposal().id(), true, null));
            assertEquals(profile.revision(), profiles.findById(profile.id()).orElseThrow().revision());
            assertEquals(profile.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
            assertEquals(profile.technicalCapabilities(), profiles.findById(profile.id()).orElseThrow().technicalCapabilities());
            assertEquals("PENDING", profiles.profileUpdate(profile.id(), staged.proposal().id()).orElseThrow().state());
            assertEquals(List.of(), profiles.protectedSections(profile.id()));
            assertFalse(conversations.readiness(profile.id(), 1).orElseThrow().sufficient());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_profile_revision WHERE profile_id=?", Integer.class, profile.id().value()));
        } finally {
            jdbc.execute("DROP TRIGGER fail_profile_protection");
        }
        var accepted = service.resolveProfileUpdate(profile.id(), staged.proposal().id(), true, null);
        assertEquals("ACCEPTED", accepted.state());
        assertEquals(2, profiles.protectedSections(profile.id()).size());
        assertEquals(profile.evidence().size() + 1, profiles.findById(profile.id()).orElseThrow().evidence().size());
        assertEquals(List.of("每周三小时"), profiles.findById(profile.id()).orElseThrow().constraints());
    }

    @Test
    void reviewAndConfirmationSupersedePendingAndReopeningKeepsAllProtection() throws Exception {
        var profile = seed();
        var service = service(gateway);
        responses.add("{\"hasNewFacts\":true,\"constraints\":[\"每周三小时\"],\"evidenceClaims\":[\"三小时\"]}");
        responses.add(reply(true, null, null, "NONE", List.of()));
        var staged = service.submit(profile.id(), command("TURN", "改成三小时", 1));
        new AssessProfileSufficiencyUseCase(profiles, new ProfileSufficiencyEvaluator(gateway, json)).assess(profile.id(), 1);
        assertEquals("SUPERSEDED", profiles.profileUpdate(profile.id(), staged.proposal().id()).orElseThrow().state());
        responses.add("{\"constraints\":[\"每周四小时\"],\"evidenceClaims\":[\"四小时\"]}");
        new com.ayywl.delveforge.application.userdiscovery.exploration.ExploreUserProfileUseCase(
                profiles, new ProfileExtraction(gateway, json)).explore(profile.id(), "改为四小时");
        var reviewProposal = profiles.pendingProfileUpdate(profile.id()).orElseThrow();
        assertEquals("REVIEWING", reviewProposal.baseStatus());
        new ConfirmUserProfileUseCase(profiles).confirm(profile.id(), 1);
        assertEquals("SUPERSEDED", service.resolveProfileUpdate(profile.id(), reviewProposal.id(), true, null).state());
        assertEquals(6, profiles.protectedSections(profile.id()).size());
        new ReopenDiscoveryUseCase(profiles).reopenDiscovery(profile.id());
        assertEquals(6, profiles.protectedSections(profile.id()).size());
        assertEquals(profile.constraints(), profiles.findById(profile.id()).orElseThrow().constraints());
    }

    @Test
    void pendingProposalSurvivesClosingAndRestartingTheBackendContext() throws Exception {
        Path database = temporary.resolve("pending-restart.db");
        UserProfile profile = skillsProfile();
        String proposalId;
        responses.add("{\"hasNewFacts\":true,\"constraints\":[\"每周三小时\"],\"evidenceClaims\":[\"三小时\"]}");
        responses.add(reply(false, null, null, "NONE", List.of()));
        try (var first = new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE).run(
                "--delveforge.persistence.database-file=" + database, "--spring.main.banner-mode=off")) {
            var storedProfiles = first.getBean(UserProfileRepository.class);
            var storedConversation = first.getBean(DiscoveryConversationRepository.class);
            storedProfiles.save(profile);
            var evaluator = new ProfileSufficiencyEvaluator(gateway, json);
            var service = new DiscoveryConversationService(storedProfiles, storedConversation,
                    mock(ProjectContextRepository.class), new RunUserDiscoveryTurnUseCase(storedProfiles,
                    new ProfileExtraction(gateway, json), evaluator), new AskDiscoveryQuestionUseCase(storedProfiles, evaluator));
            proposalId = service.submit(profile.id(), command("TURN", "每周三小时", 1)).proposal().id();
        }
        int before = calls.size();
        try (var restarted = new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.NONE).run(
                "--delveforge.persistence.database-file=" + database, "--spring.main.banner-mode=off")) {
            var storedProfiles = restarted.getBean(UserProfileRepository.class);
            var storedConversation = restarted.getBean(DiscoveryConversationRepository.class);
            assertEquals(proposalId, storedConversation.history(profile.id(), null).pendingProposal().id());
            var evaluator = new ProfileSufficiencyEvaluator(gateway, json);
            var service = new DiscoveryConversationService(storedProfiles, storedConversation,
                    mock(ProjectContextRepository.class), new RunUserDiscoveryTurnUseCase(storedProfiles,
                    new ProfileExtraction(gateway, json), evaluator), new AskDiscoveryQuestionUseCase(storedProfiles, evaluator));
            assertEquals("ACCEPTED", service.resolveProfileUpdate(profile.id(), proposalId, true, null).state());
            assertEquals(List.of("每周三小时"), storedProfiles.findById(profile.id()).orElseThrow().constraints());
            assertTrue(storedProfiles.protectedSections(profile.id()).contains("constraints"));
        }
        assertEquals(before, calls.size());
    }

    @Test
    void concurrentDuplicateApprovalReturnsOneRecordedOutcome() throws Exception {
        var profile = skillsProfile();
        var service = service(gateway);
        responses.add("{\"hasNewFacts\":true,\"constraints\":[\"每周三小时\"],\"evidenceClaims\":[\"三小时\"]}");
        responses.add(reply(false, null, null, "NONE", List.of()));
        var proposal = service.submit(profile.id(), command("TURN", "改成三小时", 1)).proposal();
        var candidate = com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates.copyOf(profile);
        com.ayywl.delveforge.application.userdiscovery.exploration.ProfileUpdatePolicy.apply(candidate,
                new com.ayywl.delveforge.application.userdiscovery.exploration.ProfileUpdatePolicy.Update(
                        proposal.proposedSections(), proposal.evidenceClaims(), true), proposal.userInput());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return profiles.resolveProfileUpdate(candidate, profile, proposal.id(), "ACCEPTED");
            });
            var second = pool.submit(() -> {
                start.await();
                return profiles.resolveProfileUpdate(candidate, profile, proposal.id(), "ACCEPTED");
            });
            start.countDown();
            var accepted = first.get(10, TimeUnit.SECONDS);
            assertEquals(accepted, second.get(10, TimeUnit.SECONDS));
            assertEquals("ACCEPTED", accepted.state());
            assertEquals(candidate.revision(), profiles.findById(profile.id()).orElseThrow().revision());
            assertEquals(profile.evidence().size() + 1, profiles.findById(profile.id()).orElseThrow().evidence().size());
        }
    }

    @Test
    void failedConversationCommitCannotLeaveAnOrphanedProposal() throws Exception {
        var profile = seed();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TRIGGER fail_proposal_turn BEFORE INSERT ON discovery_message
                BEGIN SELECT RAISE(ABORT, 'injected conversation failure'); END
                """);
        var command = command("TURN", "改成三小时", 1);
        responses.add("{\"hasNewFacts\":true,\"constraints\":[\"每周三小时\"],\"evidenceClaims\":[\"三小时\"]}");
        responses.add(reply(false, null, null, "NONE", List.of()));
        try {
            assertThrows(RuntimeException.class, () -> service(gateway).submit(profile.id(), command));
            assertTrue(profiles.pendingProfileUpdate(profile.id()).isEmpty());
            assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
            assertEquals("FAILED", conversations.operation(profile.id(), command.requestId()).orElseThrow().state());
            assertEquals(1, profiles.findById(profile.id()).orElseThrow().revision());
            assertEquals(profile.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
            assertTrue(conversations.readiness(profile.id(), 1).isEmpty());
        } finally {
            jdbc.execute("DROP TRIGGER fail_proposal_turn");
        }
    }

    @Test
    void ordinaryUnknownPivotApprovalFailureAndExplicitContinuationFormOneConsistentJourney() throws Exception {
        UserProfile initial = UserProfile.create(new UserProfileId("journey-" + UUID.randomUUID()));
        profiles.save(initial);
        var service = service(gateway);
        responses.add("""
                {"hasNewFacts":false,"technicalCapabilities":["Java REST 接口开发"],
                 "behaviors":["维护自己的 Coding Agent"],"projectGoals":["改进 Coding Agent 的使用体验"],
                 "evidenceClaims":["用户希望用 Java 改善自己的 Coding Agent"]}
                """);
        responses.add(reply(true, "你准备投入多久？", "constraints", "NONE", List.of()));
        var first = service.submit(initial.id(), command("TURN", "我做过 Java 后端，想改进自己的 Coding Agent", 1));
        assertTrue(first.result().sufficient());
        assertTrue(first.result().reviewInvitation());
        assertTrue(profiles.findById(initial.id()).orElseThrow().constraints().isEmpty());
        responses.add(reply(true, "每周能投入多少小时？", "constraints", "NONE", List.of()));
        service.submit(initial.id(), command("QUESTION", null, first.revision()));
        int beforeUnknown = calls.size();
        responses.add(reply(true, "每天能花多久？", "constraints", "DEFERRED", List.of("constraints")));
        var unknown = service.submit(initial.id(), command("TURN", "不知道。", first.revision()));
        assertEquals(beforeUnknown + 1, calls.size());
        assertEquals("REPLACED", unknown.result().previousQuestionState());
        assertNull(unknown.result().nextQuestion());
        assertTrue(conversations.memory(initial.id()).deferredAreas().isEmpty());
        assertTrue(unknown.result().sufficient());
        assertEquals(first.revision(), unknown.revision());

        responses.add("""
                {"projectGoals":["探索课程资料检索工具"],"evidenceClaims":["用户明确放下 Coding Agent，改探索资料检索"]}
                """);
        ObjectNode pivot = (ObjectNode) json.readTree(reply(true, "资料怎样整理？", "behaviors", "NONE", List.of()));
        pivot.put("nextAction", "KEEP_PENDING");
        pivot.put("assistantReply", "可以先放下 Coding Agent，换成资料检索方向；目标建议等你确认后再保存。");
        responses.add(json.writeValueAsString(pivot));
        var staged = service.submit(initial.id(), command("TURN", "不想再搞那个 coding agent 了，想做资料检索", first.revision()));
        assertEquals("PENDING", staged.proposal().state());
        assertEquals("PAUSE", staged.result().nextAction().name());
        assertTrue(staged.result().assistantReply().contains("放下 Coding Agent"));
        var beforeApproval = profiles.findById(initial.id()).orElseThrow();
        assertEquals(List.of("改进 Coding Agent 的使用体验"), beforeApproval.projectGoals());
        var accepted = service.resolveProfileUpdate(initial.id(), staged.proposal().id(), true, null);
        assertEquals(accepted, service.resolveProfileUpdate(initial.id(), staged.proposal().id(), true, null));
        var current = profiles.findById(initial.id()).orElseThrow();
        assertEquals(List.of("探索课程资料检索工具"), current.projectGoals());
        assertEquals(beforeApproval.technicalCapabilities(), current.technicalCapabilities());
        assertEquals(beforeApproval.behaviors(), current.behaviors());
        assertTrue(current.evidence().containsAll(beforeApproval.evidence()));
        assertEquals(beforeApproval.evidence().size() + 1, current.evidence().size());
        assertTrue(profiles.protectedSections(initial.id()).contains("projectGoals"));

        Command failedFollow = command("QUESTION", null, current.revision());
        assertThrows(AiGatewayException.class, () -> service.submit(initial.id(), failedFollow));
        assertEquals("FAILED", service.status(initial.id(), failedFollow.requestId(), null).state());
        assertEquals("ACCEPTED", service.profileUpdate(initial.id(), accepted.id(), null).state());
        assertEquals(current.revision(), profiles.findById(initial.id()).orElseThrow().revision());
        responses.add(reply(true, "资料检索最先服务哪门课？", "behaviors", "NONE", List.of()));
        service.submit(initial.id(), command("QUESTION", null, current.revision()));
        var restarted = new SqliteDiscoveryConversationRepository(source, manager, profiles, json);
        assertEquals("资料检索最先服务哪门课？", restarted.history(initial.id(), null).unansweredQuestion());
        assertEquals(1, new JdbcTemplate(source).queryForObject(
                "SELECT COUNT(*) FROM discovery_message WHERE profile_id=? AND question_state='ASKED'",
                Integer.class, initial.id().value()));
        assertEquals(3, restarted.history(initial.id(), null).messages().stream().filter(message -> "user".equals(message.role())).count());
        assertEquals(8, calls.size());
    }

    @Test
    void explicitChangeTopicIsAtomicIdempotentAndDoesNotDeclareARefusal() throws Exception {
        UserProfile profile = seed();
        responses.add(reply(true, "Coding Agent 准备多久完成？", "constraints", "NONE", List.of()));
        var service = service(gateway);
        service.submit(profile.id(), command("QUESTION", null, 1));
        Command change = new Command(UUID.randomUUID().toString(), "QUESTION", null, 1, null, true);
        assertThrows(AiGatewayException.class, () -> service.submit(profile.id(), change));
        assertEquals("Coding Agent 准备多久完成？", conversations.history(profile.id(), null).unansweredQuestion());
        responses.add(reply(true, "你最近想整理哪类资料？", "behaviors", "DEFERRED", List.of("constraints")));
        Command retry = new Command(UUID.randomUUID().toString(), "QUESTION", null, 1, null, true);
        var changed = service.submit(profile.id(), retry);
        int callsAfter = calls.size();
        assertEquals(changed, service.submit(profile.id(), retry));
        assertEquals(callsAfter, calls.size());
        assertEquals("REPLACED", changed.result().previousQuestionState());
        assertEquals("你最近想整理哪类资料？", conversations.history(profile.id(), null).unansweredQuestion());
        assertTrue(conversations.memory(profile.id()).deferredAreas().isEmpty());
        assertEquals(profile.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
    }

    @Test
    void blankOrMalformedCompletedOutputsFailOnceAndPreserveOriginalRequestOutcome() {
        UserProfile profile = seed();
        var service = service(gateway);
        for (String unusable : List.of("", "{\"hasNewFacts\":true", "[]")) {
            responses.add(unusable);
            Command request = command("TURN", "不想再搞 coding agent 了", 1);
            int before = calls.size();
            assertThrows(AiGatewayException.class, () -> service.submit(profile.id(), request));
            assertEquals(before + 1, calls.size());
            assertEquals("FAILED", service.status(profile.id(), request.requestId(), null).state());
            assertEquals("FAILED", service.submit(profile.id(), request).state());
            assertEquals(before + 1, calls.size());
            assertTrue(conversations.history(profile.id(), null).messages().isEmpty());
            assertTrue(profiles.pendingProfileUpdate(profile.id()).isEmpty());
            assertEquals(profile.evidence(), profiles.findById(profile.id()).orElseThrow().evidence());
        }
    }

    private String replyUnchecked(boolean sufficient) {
        try {
            return reply(sufficient, null, null, "NONE", List.of());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private UserProfile skillsProfile() {
        UserProfile profile = UserProfile.reconstitute(new UserProfileId("skills-" + UUID.randomUUID()),
                UserProfileStatus.EXPLORING, 1, List.of("阅读"), List.of(), List.of(), List.of("Java", "Spring Boot"),
                List.of("六周做个人阅读笔记接口展示后端能力"), List.of("每周两小时"),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "我会Java和Spring Boot，每周两小时做阅读笔记接口",
                        "目标、能力与时间", null, false)));
        profiles.save(profile);
        return profile;
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
