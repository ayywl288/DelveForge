package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionContentConflictException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionDiscoveryService;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 一次完整的 Product Direction Discovery 编排。
 *
 * <p>本类用替身替掉 AI Gateway 与三个 Repository Port，因此不依赖 Spring、网络、
 * 数据库或真实 LLM，但走的是与生产完全相同的链路：
 *
 * <pre>
 * 加载输入 → DirectionDiscoveryInputs → Extraction（Prompt / 解析 / 解析引用）
 *         → ProductDirectionDiscoveryService → 整批保存
 * </pre>
 *
 * <p>失败用例一律从 {@link #validResponse()} 这份完整合法的基线出发，只破坏一个条件。
 */
class DiscoverProductDirectionsUseCaseTest {

    private static final List<RepositoryProfileId> REQUESTED_PROFILES = List.of(
            DirectionDiscoveryFixtures.ACCOUNTING_PROFILE_ID,
            DirectionDiscoveryFixtures.REPORTING_PROFILE_ID);

    private final InMemoryUserProfileRepository userProfiles = new InMemoryUserProfileRepository();

    private final InMemoryDiscoveryRepositories.RepositoryProfileStub repositoryProfiles =
            new InMemoryDiscoveryRepositories.RepositoryProfileStub();

    private final InMemoryDiscoveryRepositories.ProductDirectionRecorder directions =
            new InMemoryDiscoveryRepositories.ProductDirectionRecorder();

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final AtomicInteger issuedIds = new AtomicInteger();

    private final DirectionDiscoveryExtraction extraction =
            new DirectionDiscoveryExtraction(aiGateway, new ObjectMapper());

    private final ProductDirectionDiscoveryService discoveryService =
            new ProductDirectionDiscoveryService(
                    () -> new ProductDirectionId("direction-" + issuedIds.incrementAndGet()));

    private final DiscoverProductDirectionsUseCase useCase = new DiscoverProductDirectionsUseCase(
            userProfiles, repositoryProfiles, extraction, discoveryService, directions);

    // ---------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------

    @Test
    void discoversAndPersistsThreeCandidateDirections() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        List<ProductDirection> discovered = useCase.discover(validRequest());

        assertEquals(3, discovered.size());
        assertEquals(
                List.of("个人记账 + 报表导出", "图表组件库导出能力", "记账工具的多端同步"),
                discovered.stream().map(ProductDirection::title).toList());
        for (ProductDirection direction : discovered) {
            assertEquals(ProductDirectionStatus.CANDIDATE, direction.status());
            assertEquals(DirectionDiscoveryFixtures.USER_PROFILE_ID, direction.userProfileId());
            assertEquals(DirectionDiscoveryFixtures.USER_PROFILE_REVISION,
                    direction.userProfileRevision());
        }
    }

    /** AI 只被调用一次：一次发现对应一次模型调用。 */
    @Test
    void callsTheAiExactlyOnce() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        useCase.discover(validRequest());

        assertEquals(1, aiGateway.callCount());
    }

    /** 整批保存只发生一次，且在全部校验成功之后。 */
    @Test
    void persistsTheWholeBatchOnceAfterValidation() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        useCase.discover(validRequest());

        assertEquals(1, directions.batchCount(), "整批写入一次，而不是逐条写");

        List<ProductDirection> saved = directions.saved();
        assertEquals(3, saved.size());
        assertTrue(directions.savedIdentitiesAreDistinct(), "三条方向的标识互不相同");
    }

    /**
     * 依据已经解析成真实的 EvidenceBasis，而不是 AI 调用里的临时引用。
     *
     * <p>领域类型里根本没有承载 {@code U-E1} 的位置，因此这条断言同时也是编译期的保证；
     * 这里核对的是解析结果确实对上了输入里的那几条依据。
     */
    @Test
    void resolvesTemporaryReferencesIntoRealEvidenceBasis() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        ProductDirection first = useCase.discover(validRequest()).get(0);

        assertEquals(List.of(DirectionDiscoveryFixtures.USER_BASIS_1),
                first.evidenceSupport().userNeed());
        assertEquals(List.of(DirectionDiscoveryFixtures.USER_BASIS_2),
                first.evidenceSupport().userFit());
        assertEquals(List.of(DirectionDiscoveryFixtures.ACCOUNTING_BASIS_2),
                first.evidenceSupport().reusableCapability());
    }

    /**
     * {@code repositoryProfileIds} 来自 Task 4 算出的 actual basis，而不是本次可见的全部输入：
     * 两条方向只依据了记账 Profile，一条只依据了图表 Profile。
     */
    @Test
    void recordsOnlyTheActuallyUsedRepositoryProfiles() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        List<ProductDirection> discovered = useCase.discover(validRequest());

        assertEquals(List.of(DirectionDiscoveryFixtures.ACCOUNTING_PROFILE_ID),
                discovered.get(0).repositoryProfileIds());
        assertEquals(List.of(DirectionDiscoveryFixtures.REPORTING_PROFILE_ID),
                discovered.get(1).repositoryProfileIds());
        assertEquals(List.of(DirectionDiscoveryFixtures.ACCOUNTING_PROFILE_ID),
                discovered.get(2).repositoryProfileIds());
    }

    /** 候选资产跟着 actual basis 走：方向二只依据图表 Profile，因此只能标图表资产。 */
    @Test
    void keepsCandidateAssetsAlignedWithTheActualBasis() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        List<ProductDirection> discovered = useCase.discover(validRequest());

        assertEquals(List.of(DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID),
                discovered.get(0).candidateAssetIds());
        assertEquals(List.of(DirectionDiscoveryFixtures.REPORTING_ASSET_ID),
                discovered.get(1).candidateAssetIds());
    }

    // ---------------------------------------------------------------------
    // 输入不成立时不得调用 AI
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingUserProfileWithoutCallingTheAi() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        DiscoverProductDirectionsRequest request = new DiscoverProductDirectionsRequest(
                new com.ayywl.delveforge.domain.user.UserProfileId("unknown-profile"),
                DirectionDiscoveryFixtures.USER_PROFILE_REVISION,
                REQUESTED_PROFILES);

        assertThrows(UserProfileNotFoundException.class, () -> useCase.discover(request));

        assertNothingHappened();
    }

    /** 调用方手上的版本已经过期时拒绝，而不是改用当前版本。 */
    @Test
    void rejectsStaleExpectedRevisionWithoutCallingTheAi() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        DiscoverProductDirectionsRequest request = new DiscoverProductDirectionsRequest(
                DirectionDiscoveryFixtures.USER_PROFILE_ID,
                DirectionDiscoveryFixtures.USER_PROFILE_REVISION + 1,
                REQUESTED_PROFILES);

        assertThrows(StaleUserProfileRevisionException.class, () -> useCase.discover(request));

        assertNothingHappened();
    }

    @Test
    void rejectsUnconfirmedUserProfileWithoutCallingTheAi() {
        givenReadyInputs(UserProfileStatus.REVIEWING);
        aiGateway.respond(validResponse());

        assertThrows(UserProfileNotConfirmedException.class,
                () -> useCase.discover(validRequest()));

        assertNothingHappened();
    }

    /** 任何一份 requested Profile 取不到就整体失败，不拿剩下的去问模型。 */
    @Test
    void rejectsMissingRepositoryProfileWithoutCallingTheAi() {
        givenReadyInputs();
        aiGateway.respond(validResponse());

        DiscoverProductDirectionsRequest request = new DiscoverProductDirectionsRequest(
                DirectionDiscoveryFixtures.USER_PROFILE_ID,
                DirectionDiscoveryFixtures.USER_PROFILE_REVISION,
                List.of(DirectionDiscoveryFixtures.ACCOUNTING_PROFILE_ID,
                        new RepositoryProfileId("unknown-profile")));

        assertThrows(RepositoryProfileNotFoundException.class, () -> useCase.discover(request));

        assertNothingHappened();
    }

    // ---------------------------------------------------------------------
    // 失败发生在写入之前
    // ---------------------------------------------------------------------

    /**
     * AI 调用本身失败时异常向上传播，不吞掉、不重试。
     *
     * <p>「不重试」由调用次数断言：一次发现只对应一次模型调用，失败就是失败，
     * 不能悄悄再问一次。
     */
    @Test
    void propagatesAiFailureWithoutWriting() {
        givenReadyInputs();
        aiGateway.failWith(new AiGatewayException("模型调用失败"));

        assertThrows(AiGatewayException.class, () -> useCase.discover(validRequest()));

        assertEquals(1, aiGateway.callCount(), "失败不触发重试");
        assertNothingPersisted();
    }

    /** 模型返回根本无法解析的内容时同样什么都不写。 */
    @Test
    void writesNothingWhenTheModelReturnsMalformedJson() {
        givenReadyInputs();
        aiGateway.respond("{ 这不是 json");

        assertThrows(AiGatewayException.class, () -> useCase.discover(validRequest()));

        assertEquals(1, aiGateway.callCount());
        assertNothingPersisted();
    }

    /**
     * 持久化失败时异常向上传播，且方向不会被重新生成。
     *
     * <p>到这里整条链路已经走完，方向也已经构造出来；写入失败只说明这次发现没能落地，
     * 不代表可以重来一次——因此 AI 与整批保存各只调用一次，标识也只取用了一批。
     *
     * <p>这条与 Adapter 的回滚测试是两件事：那个证明「写了一半会回滚」，
     * 这个证明「Use Case 不会把失败吞掉、也不会退而求其次返回一个成功结果」。
     */
    @Test
    void propagatesPersistenceFailure() {
        givenReadyInputs();
        aiGateway.respond(validResponse());
        directions.failBatchesWith(
                new ProductDirectionContentConflictException(new ProductDirectionId("direction-1")));

        assertThrows(ProductDirectionContentConflictException.class,
                () -> useCase.discover(validRequest()));

        assertEquals(1, aiGateway.callCount(), "持久化失败不重新调用 AI");
        assertEquals(1, directions.batchCalls(), "整批保存只被调用一次，不重试");
        assertEquals(3, issuedIds.get(), "方向只被构造了一批，不重新生成");
        assertTrue(directions.saved().isEmpty(), "没有任何方向被当作已保存的结果");
    }

    /** 模型输出不合约定时，什么都不会被写入。 */
    @Test
    void writesNothingWhenTheModelOutputIsInvalid() {
        givenReadyInputs();
        aiGateway.respond(validResponse().replace("\"U-E1\"", "\"U-E9\""));

        assertThrows(AiGatewayException.class, () -> useCase.discover(validRequest()));

        assertEquals(1, aiGateway.callCount(), "AI 确实被调用了");
        assertNothingPersisted();
    }

    /** 领域拒绝时同样什么都不写。 */
    @Test
    void writesNothingWhenTheDomainRejectsTheProposals() {
        givenReadyInputs();
        // 三条方向的 userNeed 全部指向资产侧依据：领域会拒绝（INV-D06 的来源要求）
        aiGateway.respond(validResponse()
                .replace("\"userNeed\": [\"U-E1\"]", "\"userNeed\": [\"R1-E1\"]"));

        assertThrows(
                com.ayywl.delveforge.domain.direction.ProductDirectionDiscoveryException.class,
                () -> useCase.discover(validRequest()));

        assertNothingPersisted();
    }

    @Test
    void rejectsMissingRequest() {
        assertThrows(IllegalArgumentException.class, () -> useCase.discover(null));
    }

    /**
     * 每个依赖都单独验一遍。
     *
     * <p>每次都只把一个依赖设为 {@code null}、其余给合法实例：否则断言会在前面那道检查处
     * 就失败，后面的依赖根本没被验证到。
     */
    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new DiscoverProductDirectionsUseCase(
                null, repositoryProfiles, extraction, discoveryService, directions));

        assertThrows(IllegalArgumentException.class, () -> new DiscoverProductDirectionsUseCase(
                userProfiles, null, extraction, discoveryService, directions));

        assertThrows(IllegalArgumentException.class, () -> new DiscoverProductDirectionsUseCase(
                userProfiles, repositoryProfiles, null, discoveryService, directions));

        assertThrows(IllegalArgumentException.class, () -> new DiscoverProductDirectionsUseCase(
                userProfiles, repositoryProfiles, extraction, null, directions));

        assertThrows(IllegalArgumentException.class, () -> new DiscoverProductDirectionsUseCase(
                userProfiles, repositoryProfiles, extraction, discoveryService, null));
    }

    // ---------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------

    private void givenReadyInputs() {
        givenReadyInputs(UserProfileStatus.CONFIRMED);
    }

    private void givenReadyInputs(UserProfileStatus status) {
        userProfiles.save(userProfileAt(status));
        repositoryProfiles.put(DirectionDiscoveryFixtures.accountingProfile());
        repositoryProfiles.put(DirectionDiscoveryFixtures.reportingProfile());
    }

    private static UserProfile userProfileAt(UserProfileStatus status) {
        UserProfile confirmed = DirectionDiscoveryFixtures.confirmedUserProfile();
        return UserProfile.reconstitute(
                confirmed.id(),
                status,
                confirmed.revision(),
                confirmed.interests(),
                confirmed.behaviors(),
                confirmed.painPoints(),
                confirmed.technicalCapabilities(),
                confirmed.projectGoals(),
                confirmed.constraints(),
                confirmed.evidence());
    }

    private static DiscoverProductDirectionsRequest validRequest() {
        return new DiscoverProductDirectionsRequest(
                DirectionDiscoveryFixtures.USER_PROFILE_ID,
                DirectionDiscoveryFixtures.USER_PROFILE_REVISION,
                REQUESTED_PROFILES);
    }

    /** 两个「什么都没发生」的断言：AI 没被调用，也没有任何写入。 */
    private void assertNothingHappened() {
        assertEquals(0, aiGateway.callCount(), "输入不成立时不得调用 AI");
        assertNothingPersisted();
    }

    private void assertNothingPersisted() {
        assertEquals(0, directions.batchCalls(), "失败时不得尝试写入");
        assertEquals(0, directions.batchCount(), "失败时不得写入任何方向");
        assertEquals(0, issuedIds.get(), "失败时不得构造任何方向");
    }

    /**
     * 一份完整合法的模型输出：三条方向，各自只引用本次输入提供过的依据与资产。
     *
     * <pre>
     * 方向一  依据记账 Profile（R1-E2）        候选资产 software-asset-1
     * 方向二  依据图表 Profile（R2-E1）        候选资产 software-asset-2
     * 方向三  依据记账 Profile（R1-E1、R1-E2） 候选资产 software-asset-1
     * </pre>
     */
    private static String validResponse() {
        return """
                {
                  "directions": [
                    {
                      "title": "个人记账 + 报表导出",
                      "problem": "现有记账工具缺少可导出的报表",
                      "targetProduct": "单用户桌面记账工具 + 报表导出",
                      "userFit": "用户已经在用记账工具，且技术栈匹配",
                      "candidateAssetIds": ["software-asset-1"],
                      "differentiation": "相比现有工具增加了自定义报表",
                      "technicalValue": "可复用现有报表模块的渲染能力",
                      "estimatedComplexity": "中等：主要在导出与模板部分",
                      "risks": ["模板格式复杂度可能超预期"],
                      "evidence": {
                        "userNeed": ["U-E1"],
                        "userFit": ["U-E2"],
                        "reusableCapability": ["R1-E2"]
                      }
                    },
                    {
                      "title": "图表组件库导出能力",
                      "problem": "图表组件库没有导出能力",
                      "targetProduct": "可导出的图表组件库",
                      "userFit": "用户长期关注数据可视化",
                      "candidateAssetIds": ["software-asset-2"],
                      "differentiation": "补齐导出这一环",
                      "technicalValue": "复用现有折线图组件",
                      "estimatedComplexity": "较小",
                      "risks": [],
                      "evidence": {
                        "userNeed": ["U-E1"],
                        "userFit": ["R2-E1"],
                        "reusableCapability": ["R2-E1"]
                      }
                    },
                    {
                      "title": "记账工具的多端同步",
                      "problem": "记账工具只能单端使用",
                      "targetProduct": "支持多端同步的记账工具",
                      "userFit": "用户会同时在多个设备上使用",
                      "candidateAssetIds": ["software-asset-1"],
                      "differentiation": "从单机走向多端",
                      "technicalValue": "复用现有记账模块",
                      "estimatedComplexity": "较大",
                      "risks": ["同步冲突处理复杂"],
                      "evidence": {
                        "userNeed": ["U-E2"],
                        "userFit": ["U-E1", "R1-E1"],
                        "reusableCapability": ["R1-E1"]
                      }
                    }
                  ]
                }
                """;
    }

    /** AI Gateway 替身：记录请求，返回预设内容或抛出预设失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<AiRequest> requests = new ArrayList<>();

        private String response;

        private RuntimeException failure;

        void respond(String rawResponse) {
            this.response = rawResponse;
            this.failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
            this.response = null;
        }

        int callCount() {
            return requests.size();
        }

        @Override
        public String generate(AiRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
