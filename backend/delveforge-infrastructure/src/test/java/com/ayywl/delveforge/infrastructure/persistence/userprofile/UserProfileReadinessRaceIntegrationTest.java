package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AskDiscoveryQuestionUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AssessProfileSufficiencyUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.ProfileSufficiencyEvaluator;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用正式用例和独立提交的 SQLite 事务重现资格读取与审阅保存之间的交错，不包测试外层事务。 */
@SpringBootTest(classes = UserProfileReadinessRaceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserProfileReadinessRaceIntegrationTest {

    private static final Path DATABASE_FILE = Path.of("target", "test-databases",
            "readiness-race-" + UUID.randomUUID(), "delveforge.db");
    private static final String POSITIVE = """
            {"sufficient":true,"missingAreas":[],"assistantReply":"可以继续细化。",
             "nextQuestion":"首个使用者是谁？","questionArea":"exploration","deferredAreas":[]}
            """;
    private static final String NEGATIVE = """
            {"sufficient":false,"missingAreas":["具体使用场景待明确"],"assistantReply":"还需要澄清使用场景。",
             "nextQuestion":"具体准备给谁使用？","questionArea":"behaviors","deferredAreas":[]}
            """;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteUserProfileRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.userprofile")
    static class TestApplication {
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private UserProfileRepository repository;

    private final ObjectMapper json = new ObjectMapper();

    @RepeatedTest(3)
    void revokedReadinessCannotBeRestoredByAPausedCachedReview() throws Exception {
        UserProfile original = seed();
        UserProfileId id = original.id();
        new AskDiscoveryQuestionUseCase(repository, evaluator(request -> POSITIVE)).ask(id, 4, List.of());
        assertTrue(repository.hasSufficientAssessment(id, 4));
        UserProfile loadedBeforeReview = repository.findById(id).orElseThrow();
        CountDownLatch passRead = new CountDownLatch(1);
        CountDownLatch resumeReview = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        UserProfileRepository pausedRead = pauseAfterActualPassingRead(passRead, resumeReview);
        AssessProfileSufficiencyUseCase review = new AssessProfileSufficiencyUseCase(pausedRead, evaluator(request -> {
            modelCalls.incrementAndGet();
            throw new AssertionError("缓存审阅不应隐式重新调用模型");
        }));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try {
                Future<SufficiencyAssessment> pending = executor.submit(() -> review.assess(id, 4));
                assertTrue(passRead.await(20, TimeUnit.SECONDS), "审阅应已读取真实通过标记");
                SufficiencyAssessment negative = new AskDiscoveryQuestionUseCase(repository,
                        evaluator(request -> NEGATIVE)).ask(id, 4, List.of());
                assertFalse(negative.sufficient());
                assertFalse(repository.hasSufficientAssessment(id, 4));
                assertEquals(snapshot(original), snapshot(repository.findById(id).orElseThrow()));
                resumeReview.countDown();

                Throwable failure = null;
                try {
                    pending.get(20, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    failure = exception.getCause();
                }
                UserProfile actual = repository.findById(id).orElseThrow();
                boolean readiness = repository.hasSufficientAssessment(id, 4);
                System.out.printf("READINESS_RACE outcome=%s finalStatus=%s finalReadiness=%s "
                                + "revision=%d reviewModelCalls=%d%n",
                        failure == null ? "ACCEPTED" : "REJECTED", actual.status(), readiness,
                        actual.revision(), modelCalls.get());
                assertInstanceOf(UserProfileStateException.class, failure);
                assertEquals(snapshot(original), snapshot(actual));
                assertEquals(snapshot(original), snapshot(loadedBeforeReview));
                assertFalse(readiness);
                assertEquals(0, modelCalls.get());
            } finally {
                resumeReview.countDown();
            }
        }
    }

    @Test
    void validCachedReviewRemainsAZeroModelCallOperation() {
        UserProfile original = seed();
        repository.recordSufficientAssessment(original);
        SufficiencyAssessment result = new AssessProfileSufficiencyUseCase(repository, evaluator(request -> {
            throw new AssertionError("有效缓存不应调用模型");
        })).assess(original.id(), 4);
        assertTrue(result.sufficient());
        assertEquals(UserProfileStatus.REVIEWING, result.profileStatus());
        assertEquals(4, repository.findById(original.id()).orElseThrow().revision());
        assertEquals(original.evidence(), repository.findById(original.id()).orElseThrow().evidence());
        assertEquals(UserProfileStatus.EXPLORING, original.status());
        assertTrue(repository.hasSufficientAssessment(original.id(), 4));
    }

    @Test
    void freshPositiveReviewDoesNotRequireAnExistingPassingMarker() {
        UserProfile original = seed();
        AtomicInteger modelCalls = new AtomicInteger();
        assertFalse(repository.hasSufficientAssessment(original.id(), 4));
        SufficiencyAssessment result = new AssessProfileSufficiencyUseCase(repository, evaluator(request -> {
            modelCalls.incrementAndGet();
            return "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"\"}";
        })).assess(original.id(), 4);
        assertTrue(result.sufficient());
        assertEquals(UserProfileStatus.REVIEWING, result.profileStatus());
        assertEquals(1, modelCalls.get());
        assertTrue(repository.hasSufficientAssessment(original.id(), 4));
        assertEquals(4, repository.findById(original.id()).orElseThrow().revision());
    }

    @Test
    void cachedReviewCannotCarryThePassingMarkerToAnotherRevision() {
        UserProfile original = seed();
        repository.recordSufficientAssessment(original);
        UserProfile changed = repository.findById(original.id()).orElseThrow();
        changed.updateConstraints(List.of("新的投入范围"));
        changed.beginReview();
        assertThrows(UserProfileStateException.class, () -> repository.saveReviewIfSufficient(changed, original));
        assertEquals(snapshot(original), snapshot(repository.findById(original.id()).orElseThrow()));
        assertTrue(repository.hasSufficientAssessment(original.id(), 4));
    }

    private UserProfileRepository pauseAfterActualPassingRead(
            CountDownLatch passRead, CountDownLatch resumeReview) {
        return (UserProfileRepository) Proxy.newProxyInstance(UserProfileRepository.class.getClassLoader(),
                new Class<?>[]{UserProfileRepository.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(repository, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                    if (method.getName().equals("hasSufficientAssessment")) {
                        assertEquals(true, result);
                        passRead.countDown();
                        assertTrue(resumeReview.await(20, TimeUnit.SECONDS), "审阅等待恢复超时");
                    }
                    return result;
                });
    }

    private ProfileSufficiencyEvaluator evaluator(AiGateway gateway) {
        return new ProfileSufficiencyEvaluator(gateway, json);
    }

    private UserProfile seed() {
        UserProfile profile = UserProfile.reconstitute(new UserProfileId("readiness-" + UUID.randomUUID()),
                UserProfileStatus.EXPLORING, 4, List.of("阅读"), List.of(), List.of(), List.of("Java REST接口经验"),
                List.of("六周做个人阅读笔记接口展示后端能力"), List.of("每周五小时"),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "用Java在六周做阅读笔记接口，每周五小时",
                        "用户说明目标、能力及投入范围", null, false)));
        repository.save(profile);
        return profile;
    }

    private List<Object> snapshot(UserProfile profile) {
        return List.of(profile.id(), profile.status(), profile.revision(), profile.interests(), profile.behaviors(),
                profile.painPoints(), profile.technicalCapabilities(), profile.projectGoals(), profile.constraints(),
                profile.evidence());
    }
}
