package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.asset.InMemorySoftwareAssetRepository;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryAnalysisExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryMaterialBudget;
import com.ayywl.delveforge.application.repositoryanalysis.scout.FileCatalogPayload;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetNotReadableException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 Analyze Repository 的完整编排。
 *
 * <pre>
 * 资产校验 → 解析一次 revision → 理解并读取 → AI 提议 → 创建快照 → 保存一次
 * </pre>
 *
 * <p>Workspace 与 AI 都在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络、
 * 不读取真实文件系统（AGENTS.md §10.2、§10.6）。真实 Git 集成由 Infrastructure 的
 * Workspace Adapter 测试覆盖。
 *
 * <p>整条链路会调用模型两次：先 Scout（只看描述符），再分析（看真正读到的内容）。
 * 替身因此按调用顺序返回两份预设响应。
 */
class AnalyzeRepositoryUseCaseTest {

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String LOCATION = "E:/projects/legacy-tool";

    private static final String REVISION_A = "aaaa1111";

    private static final String REVISION_B = "bbbb2222";

    private static final String POM = "pom.xml";

    private static final String APP = "src/main/App.java";

    private static final String OTHER = "src/main/Other.java";

    private static final Map<String, String> FILES_AT_A = Map.of(
            POM, "<project>spring-boot</project>",
            APP, "public class App {}",
            OTHER, "public class Other {}");

    private static final Map<String, String> FILES_AT_B = Map.of(
            "build.gradle", "plugins { id 'java' }",
            "src/main/Other.java", "public class Other {}");

    /**
     * 目录按路径升序：RF-1 = pom.xml，RF-2 = App.java，RF-3 = Other.java。
     *
     * <p>Scout 只指出 {@code App.java}——{@code Other.java} 也在源码候选里，但它没被选中。
     */
    private static final String SCOUT_RESPONSE = """
            { "focusAreas": [
                { "label": "入口", "fileRefs": ["RF-2"] },
                { "label": "再看一次", "fileRefs": ["RF-2"] },
                { "label": "还是它", "fileRefs": ["RF-2"] } ] }
            """;

    private static final String PROPOSAL = """
            {
              "purpose": "个人记账工具",
              "techStack": ["Java 21"],
              "modules": ["accounting"],
              "capabilities": ["记账"],
              "reusableAssets": [],
              "limitations": [],
              "risks": [],
              "evidence": [
                { "claim": "项目使用 Spring Boot", "sourceRef": "pom.xml" }
              ]
            }
            """;

    /**
     * 两条分支各装得下、合起来装不下的仓库：flat 目录超出预算时必须走分层。
     *
     * <pre>
     * alpha/A1.java  alpha/A2.java
     * beta/B1.java   beta/B2.java
     * pom.xml                        基础材料
     * </pre>
     */
    private static final Map<String, String> BRANCH_FILES_AT_A = Map.of(
            POM, "<project>spring-boot</project>",
            "alpha/A1.java", "class A1 {}",
            "alpha/A2.java", "class A2 {}",
            "beta/B1.java", "class B1 {}",
            "beta/B2.java", "class B2 {}");

    /** 根层一次 Region Scout 选中两条分支；随后每支一次 File Scout。 */
    private static final List<List<String>> REGION_SCRIPT = List.of(List.of("alpha", "beta"));

    /**
     * 每支的区域：每个文件各占一个区域（顺序即分支内优先级），再补一个重复区域凑够
     * 解析器要求的最少区域数。
     */
    private static final List<List<List<String>>> FILE_SCRIPT = List.of(
            List.of(List.of("alpha/A2.java"), List.of("alpha/A1.java"),
                    List.of("alpha/A2.java")),
            List.of(List.of("beta/B1.java"), List.of("beta/B2.java"), List.of("beta/B1.java")));

    private final InMemorySoftwareAssetRepository assetRepository =
            new InMemorySoftwareAssetRepository();

    private final RecordingWorkspace workspace = new RecordingWorkspace();

    private final InMemoryRepositoryProfileRepository profileRepository =
            new InMemoryRepositoryProfileRepository();

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final AnalyzeRepositoryUseCase useCase = useCase();

    @Test
    void analyzesAssetAndSavesOneProfile() {
        seedSuccessfulRun();

        RepositoryProfile profile = useCase.analyze(ASSET_ID);

        assertEquals(ASSET_ID, profile.assetId(), "Profile 必须绑定输入的资产");
        assertEquals(REVISION_A, profile.analyzedRevision());
        assertEquals("个人记账工具", profile.purpose());
        assertEquals(List.of("Java 21"), profile.techStack());
        assertEquals(List.of("accounting"), profile.modules());
        assertEquals(List.of("记账"), profile.capabilities());

        assertEquals(1, profileRepository.saveCount(), "成功时只写入一次");
        assertEquals(1, profileRepository.size(), "成功时只形成一个快照");
        assertEquals(profile.id(), profileRepository.findById(profile.id()).orElseThrow().id());
    }

    /**
     * Evidence 与它引用的文件对应：来源是 Repository，路径来自真实读取，
     * 且确认状态与可信程度不由模型决定。
     */
    @Test
    void createsRepositoryEvidenceThatPointsAtRealReadFiles() {
        seedSuccessfulRun();

        RepositoryProfile profile = useCase.analyze(ASSET_ID);

        assertEquals(1, profile.evidence().size());
        Evidence evidence = profile.evidence().get(0);
        assertEquals(EvidenceSourceType.REPOSITORY, evidence.sourceType());
        assertEquals(POM, evidence.sourceRef());
        assertTrue(workspace.readPaths().contains(evidence.sourceRef()),
                "Evidence 的路径必须是本次真正读过的路径");
        assertFalse(evidence.confirmed(), "模型得出的结论不等于已确认的事实");
        assertEquals(null, evidence.confidence(), "领域未规定可信程度口径，不由模型给出");
    }

    /**
     * HEAD 只解析一次，之后列目录与每一次读取都固定在这个 revision 上。
     */
    @Test
    void resolvesHeadOnceAndPinsEveryOperationToIt() {
        seedSuccessfulRun();

        useCase.analyze(ASSET_ID);

        assertEquals(1, workspace.headRevisionCalls(), "HEAD 只能解析一次");
        assertEquals(List.of(REVISION_A), workspace.listedRevisions());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION_A::equals),
                "所有文件读取都必须带着同一个 revision: " + workspace.readRevisions());
    }

    /**
     * 分析期间源 Repository 前移 HEAD 时，本次分析仍然全部来自最初解析出的 revision。
     */
    @Test
    void keepsUsingTheResolvedRevisionWhenHeadMovesDuringAnalysis() {
        seedSuccessfulRun();
        workspace.givenRevision(REVISION_B, FILES_AT_B);
        workspace.moveHeadAfterNextListing(REVISION_B);

        RepositoryProfile profile = useCase.analyze(ASSET_ID);

        assertEquals(REVISION_A, profile.analyzedRevision());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION_A::equals),
                "HEAD 前移之后仍必须读取最初解析出的 revision: " + workspace.readRevisions());
        assertEquals(POM, profile.evidence().get(0).sourceRef());
    }

    /**
     * 最终分析拿到的是真实读到的内容，而不是描述符或目录。
     */
    @Test
    void sendsActuallyReadContentsToTheFinalAnalysis() {
        seedSuccessfulRun();

        useCase.analyze(ASSET_ID);

        assertEquals(2, aiGateway.callCount(), "一次 Scout + 一次最终分析");
        String finalRequest = aiGateway.lastRequest().messages().get(1).content();
        assertTrue(finalRequest.contains(POM));
        assertTrue(finalRequest.contains("<project>spring-boot</project>"),
                "最终分析必须拿到真实读到的内容");
        assertTrue(finalRequest.contains("public class App {}"));
    }

    /**
     * 只读计划里被选中的文件：Scout 没有指出的源码不会被读取。
     *
     * <p>这一条同时证明旧的确定性选材策略**没有**在这条链路上生效——旧策略会按类别
     * 轮转把同一类里的源码都读进来，包括 {@code Other.java}。
     * 现在读哪些文件由 Scout 决定，它没有指出 {@code Other.java}。
     */
    @Test
    void readsOnlyThePlannedFilesAndNeverFallsBackToSampling() {
        seedSuccessfulRun();

        useCase.analyze(ASSET_ID);

        assertTrue(workspace.readPaths().contains(POM), "基础材料应当被读取");
        assertTrue(workspace.readPaths().contains(APP), "Scout 指出的源码应当被读取");
        assertFalse(workspace.readPaths().contains(OTHER),
                "Scout 没有指出的源码不得被读取，更不能退回按类别采样: " + workspace.readPaths());
    }

    /**
     * 只读边界：整条链路里没有任何修改能力的调用。
     */
    @Test
    void neverUsesMutationCapability() {
        seedSuccessfulRun();

        useCase.analyze(ASSET_ID);

        assertEquals(0, workspace.mutationCalls());
    }

    /**
     * 每次分析都形成一个新的快照，而不是改写上一次的结果（DOMAIN_MODEL.md §10.4）。
     */
    @Test
    void createsANewSnapshotForEachAnalysis() {
        seedSuccessfulRun(2);

        RepositoryProfile first = useCase.analyze(ASSET_ID);
        RepositoryProfile second = useCase.analyze(ASSET_ID);

        assertNotEquals(first.id(), second.id());
        assertEquals(2, profileRepository.size());
        assertEquals(2, profileRepository.saveCount());
    }

    // ---------------------------------------------------------------------
    // 前置条件
    // ---------------------------------------------------------------------

    @Test
    void rejectsUnknownAsset() {
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);

        assertThrows(SoftwareAssetNotFoundException.class,
                () -> useCase.analyze(new SoftwareAssetId("unknown-asset")));

        assertEquals(0, profileRepository.saveCount());
        assertEquals(0, aiGateway.callCount());
    }

    /**
     * INV-A01：资产自身不允许读取时，分析在第一步就被拒绝，不碰 Workspace 也不调用 AI。
     */
    @Test
    void rejectsAssetWithoutReadPermission() {
        seedAsset(false);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);

        assertThrows(SoftwareAssetNotReadableException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, workspace.headRevisionCalls());
        assertEquals(0, aiGateway.callCount());
        assertEquals(0, profileRepository.saveCount());
    }

    @Test
    void rejectsLocationThatIsNotAReadableRepository() {
        seedAsset(true);
        workspace.givenUnreadableRepository();

        assertThrows(RepositoryNotAnalyzableException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, workspace.headRevisionCalls(), "不可读时不应继续解析 revision");
        assertEquals(0, aiGateway.callCount());
        assertEquals(0, profileRepository.saveCount());
    }

    /**
     * 没有任何源码候选时明确失败，而不是让模型对着空材料编造结论，也不写入任何快照。
     *
     * <p>本版本不做「只分析基础材料」的降级。
     */
    @Test
    void doesNotSaveProfileWhenThereIsNoSourceCandidate() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, Map.of(POM, "<project/>", "README.md", "# demo"));
        workspace.givenHeadRevision(REVISION_A);

        assertThrows(RepositoryNotAnalyzableException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, aiGateway.callCount(), "没有源码候选时不调用 AI");
        assertEquals(0, profileRepository.saveCount());
    }

    // ---------------------------------------------------------------------
    // 分层 Scout 路径
    // ---------------------------------------------------------------------

    /**
     * flat 目录超出预算时走分层，整次分析照常完成：基础材料与分层选出的源码都进入最终分析，
     * 并保存一次快照。
     */
    @Test
    void savesProfileWhenTheHierarchicalPathIsTaken() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, BRANCH_FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);

        ScoutPathGateway gateway = branchGateway();
        gateway.respondAll(REGION_SCRIPT, FILE_SCRIPT, PROPOSAL);

        RepositoryProfile profile = analyzeWith(gateway, flatCatalogBytes() - 1);

        assertEquals(1, profileRepository.saveCount(), "成功时只写入一次");
        assertEquals(REVISION_A, profile.analyzedRevision());
        assertEquals(List.of(POM, "alpha/A2.java", "beta/B1.java", "alpha/A1.java",
                        "beta/B2.java"),
                workspace.readPaths(), "分层合并的顺序就是读取顺序");
        String finalRequest = gateway.requests().get(gateway.requests().size() - 1)
                .messages().get(1).content();
        assertTrue(finalRequest.contains("<project>spring-boot</project>"),
                "基础材料必须进入最终分析");
        assertTrue(finalRequest.contains("class A2 {}"), "分层选出的源码必须进入最终分析");
    }

    /**
     * 分层 Scout 失败时整次分析失败：不写快照，也不退回「把超限的 flat 目录发出去」。
     */
    @Test
    void doesNotSaveProfileWhenHierarchicalScoutFails() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, BRANCH_FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);

        ScoutPathGateway gateway = branchGateway();
        gateway.respondAll(REGION_SCRIPT, FILE_SCRIPT);
        gateway.answerRegionWithUnknownReference();

        assertThrows(AiGatewayException.class,
                () -> analyzeWith(gateway, flatCatalogBytes() - 1));

        assertEquals(0, profileRepository.saveCount(), "分层失败时不得写入任何快照");
        assertEquals(0, gateway.fileCalls(), "失败之后不得再调用 File Scout");
        assertTrue(workspace.readPaths().isEmpty(), "分层失败时不读取任何文件");
    }

    // ---------------------------------------------------------------------
    // 失败原子性：任何一步失败都不留下快照
    // ---------------------------------------------------------------------

    @Test
    void doesNotSaveProfileWhenWorkspaceReadFails() {
        seedSuccessfulRun();
        workspace.failReadsWith(new WorkspaceException("读取失败"));

        assertThrows(WorkspaceException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    /**
     * Scout 失败时整次分析失败，不退回旧策略。
     */
    @Test
    void doesNotSaveProfileWhenScoutFails() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.failOnCall(1, new AiGatewayException("Scout 调用失败"));

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
        assertTrue(workspace.readPaths().isEmpty(), "Scout 失败时不读取任何文件");
    }

    @Test
    void doesNotSaveProfileWhenScoutOutputCannotBeParsed() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respondAll("{\"focusAreas\":[]}");

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    /**
     * 最终分析调用失败时同样不留下快照——此时材料已经读过了，但仍然没有写入。
     */
    @Test
    void doesNotSaveProfileWhenTheFinalAnalysisFails() {
        seedSuccessfulRun();
        aiGateway.failOnCall(2, new AiGatewayException("分析调用失败"));

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    @Test
    void doesNotSaveProfileWhenFinalAnalysisOutputCannotBeParsed() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respondAll(SCOUT_RESPONSE, "{\"purpose\":\"只有一个字段\"}");

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    /**
     * sourceRef 校验在本流程中依然生效：模型指向没有提供的文件时整次分析失败。
     */
    @Test
    void doesNotSaveProfileWhenEvidenceReferencesAFileThatWasNotSent() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respondAll(SCOUT_RESPONSE,
                PROPOSAL.replace(POM, "some/nonexistent/file.java"));

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    /**
     * 领域拒绝发生在保存之前。
     */
    @Test
    void doesNotSaveProfileWhenDomainRejectsTheAnalysis() {
        seedAsset(true);
        workspace.givenRevision("  ", FILES_AT_A);
        workspace.givenHeadRevision("  ");
        aiGateway.respondAll(SCOUT_RESPONSE, PROPOSAL);

        assertThrows(IllegalArgumentException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    @Test
    void rejectsMissingDependencies() {
        RepositoryUnderstanding understanding = understanding();
        RepositoryAnalysisExtraction extraction =
                new RepositoryAnalysisExtraction(aiGateway, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                null, workspace, understanding, extraction, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, null, understanding, extraction, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, workspace, null, extraction, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, workspace, understanding, null, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, workspace, understanding, extraction, null));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private AnalyzeRepositoryUseCase useCase() {
        return new AnalyzeRepositoryUseCase(
                assetRepository,
                workspace,
                understanding(),
                new RepositoryAnalysisExtraction(aiGateway, new ObjectMapper()),
                profileRepository);
    }

    /** 走分层路径的一次完整分析。 */
    private RepositoryProfile analyzeWith(ScoutPathGateway gateway, int maxCatalogBytes) {
        return new AnalyzeRepositoryUseCase(
                assetRepository,
                workspace,
                understanding(gateway, maxCatalogBytes),
                new RepositoryAnalysisExtraction(gateway, new ObjectMapper()),
                profileRepository)
                .analyze(ASSET_ID);
    }

    private ScoutPathGateway branchGateway() {
        return new ScoutPathGateway(() -> workspace.readPaths().size());
    }

    /**
     * 这份夹具下整份源码目录的字节数，也就是「分层与否」的判据。
     *
     * <p>量的是 SCOUT_SOURCE 那一组的载荷，与生产走同一个渲染入口。
     */
    private int flatCatalogBytes() {
        RepositoryMap map = new RepositoryMapBuilder(workspace)
                .build(new WorkspaceRef(LOCATION), REVISION_A);
        return new FileCatalogPayload(new ObjectMapper())
                .payloadBytes(REVISION_A, map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE));
    }

    private RepositoryUnderstanding understanding() {
        return understanding(aiGateway, 65_536);
    }

    private RepositoryUnderstanding understanding(AiGateway gateway, int maxCatalogBytes) {
        RepositoryMaterialBudget foundation = new RepositoryMaterialBudget(12, 32_768, 98_304);
        RepositoryMaterialBudget targetedSource =
                new RepositoryMaterialBudget(18, 65_536, 163_840);
        return UnderstandingFixtures.understanding(
                gateway, workspace, foundation, targetedSource, maxCatalogBytes);
    }

    private void seedSuccessfulRun() {
        seedSuccessfulRun(1);
    }

    private void seedSuccessfulRun(int runs) {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);

        List<String> responses = new ArrayList<>();
        for (int index = 0; index < runs; index++) {
            responses.add(SCOUT_RESPONSE);
            responses.add(PROPOSAL);
        }
        aiGateway.respondAll(responses.toArray(String[]::new));
    }

    private void seedAsset(boolean readPermissionAllowed) {
        assetRepository.save(SoftwareAsset.create(
                ASSET_ID,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                LOCATION,
                readPermissionAllowed,
                null,
                UsageAuthorization.UNCLEAR));
    }

    /** AI Gateway 替身：按调用顺序返回预设内容，可指定某一次调用失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<AiRequest> requests = new ArrayList<>();

        private final List<String> responses = new ArrayList<>();

        private int failOnCall = -1;

        private RuntimeException failure;

        void respondAll(String... rawResponses) {
            responses.clear();
            responses.addAll(List.of(rawResponses));
            failOnCall = -1;
            failure = null;
        }

        void failOnCall(int callNumber, RuntimeException exception) {
            this.failOnCall = callNumber;
            this.failure = exception;
        }

        AiRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        int callCount() {
            return requests.size();
        }

        @Override
        public String generate(AiRequest request) {
            requests.add(request);
            if (requests.size() == failOnCall) {
                throw failure;
            }
            if (responses.isEmpty()) {
                throw new AiGatewayException("替身没有更多预设响应");
            }
            return responses.remove(0);
        }
    }
}
