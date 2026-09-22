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
import com.ayywl.delveforge.application.repositoryanalysis.asset.InMemorySoftwareAssetRepository;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryAnalysisExtraction;
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
 * 验证 Analyze Repository 的编排：只读读取 → AI 提议 → 创建快照 → 保存。
 *
 * <p>Workspace 与 AI 都在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络、
 * 不读取真实文件系统（AGENTS.md §10.2、§10.6）。真实 Git 集成由 Infrastructure 的
 * Workspace Adapter 测试覆盖。
 */
class AnalyzeRepositoryUseCaseTest {

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String LOCATION = "E:/projects/legacy-tool";

    private static final String REVISION_A = "aaaa1111";

    private static final String REVISION_B = "bbbb2222";

    private static final Map<String, String> FILES_AT_A = Map.of(
            "pom.xml", "<project>spring-boot</project>",
            "src/main/App.java", "public class App {}");

    private static final Map<String, String> FILES_AT_B = Map.of(
            "build.gradle", "plugins { id 'java' }",
            "src/main/Other.java", "public class Other {}");

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

    private final InMemorySoftwareAssetRepository assetRepository =
            new InMemorySoftwareAssetRepository();

    private final RecordingWorkspace workspace = new RecordingWorkspace();

    private final InMemoryRepositoryProfileRepository profileRepository =
            new InMemoryRepositoryProfileRepository();

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final AnalyzeRepositoryUseCase useCase = useCaseWithDefaultPolicy();

    @Test
    void analyzesAssetAndSavesOneProfile() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond(PROPOSAL);

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
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond(PROPOSAL);

        RepositoryProfile profile = useCase.analyze(ASSET_ID);

        assertEquals(1, profile.evidence().size());
        Evidence evidence = profile.evidence().get(0);
        assertEquals(EvidenceSourceType.REPOSITORY, evidence.sourceType());
        assertEquals("pom.xml", evidence.sourceRef());
        assertTrue(workspace.readPaths().contains(evidence.sourceRef()),
                "Evidence 的路径必须是本次真正读过的路径");
        assertEquals("项目使用 Spring Boot", evidence.claim());
        assertFalse(evidence.confirmed(), "模型得出的结论不等于已确认的事实");
        assertEquals(null, evidence.confidence(), "领域未规定可信程度口径，不由模型给出");
    }

    /**
     * HEAD 只解析一次，之后所有读取都固定在这个 revision 上。
     */
    @Test
    void resolvesHeadOnceAndPinsEveryReadToIt() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond(PROPOSAL);

        useCase.analyze(ASSET_ID);

        assertEquals(1, workspace.headRevisionCalls(), "HEAD 只能解析一次");
        assertEquals(List.of(REVISION_A), workspace.listedRevisions());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION_A::equals),
                "所有文件读取都必须带着同一个 revision: " + workspace.readRevisions());
    }

    /**
     * 分析期间源 Repository 前移 HEAD 时，本次分析仍然全部来自最初解析出的 revision。
     *
     * <p>这里让两个 revision 有完全不同的文件：如果实现中途重新解析了 HEAD，
     * 材料就会变成另一个 revision 的树，模型给出的 pom.xml 依据将无法通过
     * sourceRef 校验，本次分析会直接失败。
     */
    @Test
    void keepsUsingTheResolvedRevisionWhenHeadMovesDuringAnalysis() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        workspace.givenRevision(REVISION_B, FILES_AT_B);
        workspace.givenHeadRevision(REVISION_A);
        workspace.moveHeadAfterNextListing(REVISION_B);
        aiGateway.respond(PROPOSAL);

        RepositoryProfile profile = useCase.analyze(ASSET_ID);

        assertEquals(REVISION_A, profile.analyzedRevision());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION_A::equals),
                "HEAD 前移之后仍必须读取最初解析出的 revision: " + workspace.readRevisions());
        assertEquals("pom.xml", profile.evidence().get(0).sourceRef());
    }

    /**
     * 材料的路径来自真实读取链路：先列目录、再按同一路径读取，Evidence 的 sourceRef
     * 因此能指回实际读过的文件。
     */
    @Test
    void sourcesMaterialPathsFromRealListingsAndReads() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond(PROPOSAL);

        useCase.analyze(ASSET_ID);

        assertEquals(List.of("pom.xml", "src/main/App.java"), workspace.readPaths(),
                "只读取列目录得到的文件，顺序按相对路径升序");
        String request = aiGateway.lastRequest().messages().get(1).content();
        assertTrue(request.contains("pom.xml"));
        assertTrue(request.contains("<project>spring-boot</project>"),
                "材料必须带上真实读到的内容");
        assertTrue(request.contains("src/main/App.java"));
    }

    /**
     * 每次分析都形成一个新的快照，而不是改写上一次的结果（DOMAIN_MODEL.md §10.4）。
     */
    @Test
    void createsANewSnapshotForEachAnalysis() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond(PROPOSAL);

        RepositoryProfile first = useCase.analyze(ASSET_ID);
        RepositoryProfile second = useCase.analyze(ASSET_ID);

        assertNotEquals(first.id(), second.id());
        assertEquals(2, profileRepository.size());
        assertEquals(2, profileRepository.saveCount());
    }

    /**
     * 只读边界：整条链路里没有任何修改能力的调用。
     */
    @Test
    void neverUsesMutationCapability() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond(PROPOSAL);

        useCase.analyze(ASSET_ID);

        assertEquals(0, workspace.mutationCalls());
    }

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
     * 没有任何可分析材料时明确失败，而不是让模型对着空材料编造结论，
     * 也不写入任何快照。
     */
    @Test
    void doesNotSaveProfileWhenThereIsNoAnalyzableMaterial() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, Map.of("logo.png", "not really an image"));
        workspace.givenHeadRevision(REVISION_A);

        assertThrows(RepositoryNotAnalyzableException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, aiGateway.callCount(), "没有材料时不调用 AI");
        assertEquals(0, profileRepository.saveCount());
    }

    @Test
    void doesNotSaveProfileWhenWorkspaceReadFails() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        workspace.failReadsWith(new WorkspaceException("读取失败"));

        assertThrows(WorkspaceException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, aiGateway.callCount());
        assertEquals(0, profileRepository.saveCount());
    }

    @Test
    void doesNotSaveProfileWhenAiCallFails() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.failWith(new AiGatewayException("模型调用失败"));

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    @Test
    void doesNotSaveProfileWhenAiOutputCannotBeParsed() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond("{\"purpose\":\"只有一个字段\"}");

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    /**
     * Task 6 的 sourceRef 校验在本流程中依然生效：模型指向没有提供的文件时整次分析失败。
     */
    @Test
    void doesNotSaveProfileWhenEvidenceReferencesAFileThatWasNotSent() {
        seedAsset(true);
        workspace.givenRevision(REVISION_A, FILES_AT_A);
        workspace.givenHeadRevision(REVISION_A);
        aiGateway.respond(PROPOSAL.replace("pom.xml", "some/nonexistent/file.java"));

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    /**
     * 领域拒绝发生在保存之前。
     *
     * <p>这里用一个会违反领域约束的输入触发拒绝：Workspace 返回空白 revision。
     * 真实 Adapter 不会返回空 revision（空仓库在取 revision 时就已经失败），
     * 本测试验证的是「领域拒绝时不会有任何写入」这条顺序。
     */
    @Test
    void doesNotSaveProfileWhenDomainRejectsTheAnalysis() {
        seedAsset(true);
        workspace.givenRevision("  ", FILES_AT_A);
        workspace.givenHeadRevision("  ");
        aiGateway.respond(PROPOSAL);

        assertThrows(IllegalArgumentException.class, () -> useCase.analyze(ASSET_ID));

        assertEquals(0, profileRepository.saveCount());
    }

    @Test
    void rejectsMissingDependencies() {
        RepositoryAnalysisExtraction extraction =
                new RepositoryAnalysisExtraction(aiGateway, new ObjectMapper());
        RepositoryAnalysisMaterialCollector collector = new RepositoryAnalysisMaterialCollector(
                workspace, RepositoryAnalysisMaterialPolicy.mvpDefault());

        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                null, workspace, collector, extraction, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, null, collector, extraction, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, workspace, null, extraction, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, workspace, collector, null, profileRepository));
        assertThrows(IllegalArgumentException.class, () -> new AnalyzeRepositoryUseCase(
                assetRepository, workspace, collector, extraction, null));
    }

    private AnalyzeRepositoryUseCase useCaseWithDefaultPolicy() {
        return new AnalyzeRepositoryUseCase(
                assetRepository,
                workspace,
                new RepositoryAnalysisMaterialCollector(
                        workspace, RepositoryAnalysisMaterialPolicy.mvpDefault()),
                new RepositoryAnalysisExtraction(aiGateway, new ObjectMapper()),
                profileRepository);
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

    /** AI Gateway 替身：记录收到的请求，并返回预设内容或抛出预设失败。 */
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

        AiRequest lastRequest() {
            return requests.get(requests.size() - 1);
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
