package com.ayywl.delveforge.infrastructure.repositoryanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryAnalysisExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.AnalyzeRepositoryUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryAnalysisMaterialCollector;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryAnalysisMaterialPolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;
import com.ayywl.delveforge.infrastructure.persistence.repositoryprofile.RepositoryProfileEvidenceMapper;
import com.ayywl.delveforge.infrastructure.persistence.repositoryprofile.RepositoryProfileMapper;
import com.ayywl.delveforge.infrastructure.persistence.repositoryprofile.RepositoryProfileSectionItemMapper;
import com.ayywl.delveforge.infrastructure.persistence.repositoryprofile.SqliteRepositoryProfileRepository;
import com.ayywl.delveforge.infrastructure.persistence.softwareasset.SqliteSoftwareAssetRepository;
import com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 端到端验证 Analyze Repository：真实 Git Repository + 真实 SQLite。
 *
 * <p>这是唯一一个把整条链路接在真实技术上的测试：Workspace 是真实的
 * {@link GitWorkspaceAdapter}（真实 {@code git} 命令、真实 commit tree），Persistence 是
 * 真实 SQLite + Flyway + MyBatis-Plus，只有 AI 在 Port 边界用替身替代（AGENTS.md §10.3：
 * 外部 AI 与本地实现是两类不同的能力，前者不应当在自动化测试里产生真实调用）。
 *
 * <p>Application 层的编排行为（失败路径、revision 固定等）由
 * {@code AnalyzeRepositoryUseCaseTest} 用 Port 替身覆盖；本类回答的是另一个问题：
 * 这条链路在真实 Git 与真实数据库上确实能跑通，并且不修改源 Repository。
 */
@SpringBootTest(
        classes = AnalyzeRepositoryWorkflowIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AnalyzeRepositoryWorkflowIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final GitTestRepositories REPOSITORIES = new GitTestRepositories();

    private static final StubAiGateway AI_GATEWAY = new StubAiGateway();

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String PROPOSAL = """
            {
              "purpose": "个人记账工具",
              "techStack": ["Java 21", "Spring Boot"],
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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @AfterAll
    static void deleteTestRepositories() throws IOException {
        REPOSITORIES.deleteAll();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteSoftwareAssetRepository.class,
            SqliteRepositoryProfileRepository.class, GitWorkspaceAdapter.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence")
    static class TestApplication {

        @Bean
        AiGateway aiGateway() {
            return AI_GATEWAY;
        }

        @Bean
        RepositoryAnalysisMaterialCollector repositoryAnalysisMaterialCollector(
                WorkspaceReadPort workspaceReadPort) {
            return new RepositoryAnalysisMaterialCollector(
                    workspaceReadPort, RepositoryAnalysisMaterialPolicy.mvpDefault());
        }

        @Bean
        AnalyzeRepositoryUseCase analyzeRepositoryUseCase(
                SoftwareAssetRepository softwareAssetRepository,
                WorkspaceReadPort workspaceReadPort,
                RepositoryAnalysisMaterialCollector repositoryAnalysisMaterialCollector,
                RepositoryProfileRepository repositoryProfileRepository,
                ObjectMapper objectMapper) {
            return new AnalyzeRepositoryUseCase(
                    softwareAssetRepository,
                    workspaceReadPort,
                    repositoryAnalysisMaterialCollector,
                    new RepositoryAnalysisExtraction(AI_GATEWAY, objectMapper),
                    repositoryProfileRepository);
        }
    }

    @Autowired
    private AnalyzeRepositoryUseCase useCase;

    @Autowired
    private SoftwareAssetRepository softwareAssetRepository;

    @Autowired
    private RepositoryProfileRepository repositoryProfileRepository;

    @Autowired
    private WorkspaceReadPort workspace;

    @Autowired
    private RepositoryProfileMapper profileMapper;

    @Autowired
    private RepositoryProfileSectionItemMapper sectionItemMapper;

    @Autowired
    private RepositoryProfileEvidenceMapper evidenceMapper;

    /**
     * 解析 revision 之后、材料读取完成之前推进真实 HEAD：本次分析仍然只能用最初那个
     * revision 的内容，数据库里记录的也必须是它。
     *
     * <p>HEAD 的推进发生在列目录这一步（真实 git commit），因此材料读取确实发生在
     * HEAD 已经移动之后——如果实现中途重新解析 HEAD，这里会读到新提交的内容。
     */
    @Test
    void keepsTheResolvedRevisionWhenRealHeadMovesDuringAnalysis() throws Exception {
        Path repository = REPOSITORIES.createCommitted("head-moves", Map.of(
                "pom.xml", "<project>old</project>"));
        String resolvedRevision = GitTestRepositories.headRevision(repository);
        registerAsset(repository);
        AI_GATEWAY.respond(PROPOSAL);

        WorkspaceReadPort movingHead = new MoveHeadOnFirstListing(
                new GitWorkspaceAdapter(), repository, "pom.xml", "<project>new</project>");

        RepositoryProfile profile = analyzeWith(movingHead);

        assertEquals(resolvedRevision, profile.analyzedRevision(),
                "Profile 必须记录解析时那个 revision");
        String sentMaterial = AI_GATEWAY.lastRequest().messages().get(1).content();
        assertTrue(sentMaterial.contains("<project>old</project>"),
                "AI 必须收到旧 revision 的内容: " + sentMaterial);
        assertFalse(sentMaterial.contains("<project>new</project>"),
                "新提交的内容不得进入本次分析");

        RepositoryProfile reloaded =
                repositoryProfileRepository.findById(profile.id()).orElseThrow();
        assertEquals(resolvedRevision, reloaded.analyzedRevision());
        assertNotEquals(reloaded.analyzedRevision(), GitTestRepositories.headRevision(repository),
                "测试前提：源 Repository 的 HEAD 确实已经前移");
    }

    /**
     * 失败不留下任何写入。
     *
     * <p>直接检查三张 Profile 表没有新增行，而不是依赖测试结束时的回滚——
     * 回滚会让「已经写了一半」的缺陷同样看不见。
     */
    @Test
    void leavesNoRowsWhenTheAiCallFails() throws Exception {
        Path repository = REPOSITORIES.createCommitted("ai-fails", Map.of(
                "pom.xml", "<project>spring-boot</project>"));
        registerAsset(repository);
        AI_GATEWAY.failWith(new AiGatewayException("模型调用失败"));

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertNoProfileRowsWritten();
    }

    @Test
    void leavesNoRowsWhenTheProposalIsNotUsable() throws Exception {
        Path repository = REPOSITORIES.createCommitted("bad-proposal", Map.of(
                "pom.xml", "<project>spring-boot</project>"));
        registerAsset(repository);
        // 依据指向本次没有提供的文件：Task 6 的校验会拒绝整次分析
        AI_GATEWAY.respond(PROPOSAL.replace("pom.xml", "not/sent.java"));

        assertThrows(AiGatewayException.class, () -> useCase.analyze(ASSET_ID));

        assertNoProfileRowsWritten();
    }

    @Test
    void analyzesRealRepositoryAndPersistsProfileWithoutTouchingTheSource() throws Exception {
        Path repository = REPOSITORIES.createCommitted("analyzed", Map.of(
                "README.md", "# legacy tool\n",
                "pom.xml", "<project>spring-boot</project>",
                "src/main/App.java", "public class App {}"));
        String headRevision = GitTestRepositories.headRevision(repository);
        registerAsset(repository);
        AI_GATEWAY.respond(PROPOSAL);

        Map<String, String> before = GitTestRepositories.snapshot(repository);

        RepositoryProfile profile = useCase.analyze(ASSET_ID);

        // 分析针对的是当前提交，且与 Profile 内容一致
        assertEquals(ASSET_ID, profile.assetId());
        assertEquals(headRevision, profile.analyzedRevision());
        assertEquals("个人记账工具", profile.purpose());
        assertEquals(List.of("Java 21", "Spring Boot"), profile.techStack());

        // 快照真的写进了数据库，并且能按标识读回
        RepositoryProfile reloaded =
                repositoryProfileRepository.findById(profile.id()).orElseThrow();
        assertEquals(profile.id(), reloaded.id());
        assertEquals(headRevision, reloaded.analyzedRevision());
        assertEquals("个人记账工具", reloaded.purpose());
        assertEquals(List.of("accounting"), reloaded.modules());
        assertEquals(profile.evidence(), reloaded.evidence());

        // Evidence 指向的是真实存在、且确实能按该 revision 读到的文件
        Evidence evidence = reloaded.evidence().get(0);
        assertEquals(EvidenceSourceType.REPOSITORY, evidence.sourceType());
        assertEquals("pom.xml", evidence.sourceRef());
        assertFalse(evidence.confirmed());
        assertEquals(
                "<project>spring-boot</project>",
                workspace.readFile(
                        new WorkspaceRef(repository.toString()),
                        reloaded.analyzedRevision(),
                        evidence.sourceRef()),
                "Evidence 的路径必须能在它记录的 revision 上真实读到");

        assertEquals(before, GitTestRepositories.snapshot(repository),
                "Repository Analysis 必须保持只读：源码与 Git 状态都不得被修改");
    }

    /**
     * 同一个资产分析两次会得到两个独立快照：新的分析不覆盖旧的分析结果。
     */
    @Test
    void keepsOneSnapshotPerAnalysis() throws Exception {
        Path repository = REPOSITORIES.createCommitted("analyzed-twice", Map.of(
                "pom.xml", "<project>spring-boot</project>"));
        registerAsset(repository);
        AI_GATEWAY.respond(PROPOSAL);

        RepositoryProfile first = useCase.analyze(ASSET_ID);
        RepositoryProfile second = useCase.analyze(ASSET_ID);

        assertTrue(repositoryProfileRepository.findById(first.id()).isPresent());
        assertTrue(repositoryProfileRepository.findById(second.id()).isPresent());
        assertEquals(first.analyzedRevision(), second.analyzedRevision());
        assertEquals(
                "个人记账工具",
                repositoryProfileRepository.findById(first.id()).orElseThrow().purpose(),
                "后一次分析不得改写前一个快照");
    }

    /** 用给定的 Workspace 能力跑一次分析，其余依赖使用真实的那些。 */
    private RepositoryProfile analyzeWith(WorkspaceReadPort workspacePort) {
        return new AnalyzeRepositoryUseCase(
                softwareAssetRepository,
                workspacePort,
                new RepositoryAnalysisMaterialCollector(
                        workspacePort, RepositoryAnalysisMaterialPolicy.mvpDefault()),
                new RepositoryAnalysisExtraction(AI_GATEWAY, new ObjectMapper()),
                repositoryProfileRepository)
                .analyze(ASSET_ID);
    }

    /**
     * 直接数三张 Profile 表，而不是依赖测试结束时的回滚：回滚会让「已经写了一半」
     * 这类缺陷同样看不见。
     */
    private void assertNoProfileRowsWritten() {
        assertEquals(0, profileMapper.selectCount(null).intValue(), "不得写入 Profile 行");
        assertEquals(0, sectionItemMapper.selectCount(null).intValue(), "不得写入内容行");
        assertEquals(0, evidenceMapper.selectCount(null).intValue(), "不得写入 Evidence 行");
    }

    private void registerAsset(Path repository) {
        softwareAssetRepository.save(SoftwareAsset.create(
                ASSET_ID,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                repository.toString(),
                true,
                null,
                UsageAuthorization.UNCLEAR));
    }

    /**
     * 在第一次列目录之前推进真实 HEAD 的 Workspace 装饰器。
     *
     * <p>调用方在此之前已经解析过 revision，因此这次真实提交发生在「解析之后、
     * 材料读取之前」——正是「分析期间 HEAD 前移」这一交错点。装饰器只做这一件事，
     * 其余调用原样交给真实的 {@link GitWorkspaceAdapter}。
     */
    private static final class MoveHeadOnFirstListing implements WorkspaceReadPort {

        private final WorkspaceReadPort delegate;
        private final Path repository;
        private final String path;
        private final String content;
        private boolean moved;

        MoveHeadOnFirstListing(
                WorkspaceReadPort delegate, Path repository, String path, String content) {
            this.delegate = delegate;
            this.repository = repository;
            this.path = path;
            this.content = content;
        }

        @Override
        public List<WorkspaceEntry> listEntries(
                WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
            moveHeadOnce();
            return delegate.listEntries(workspace, revision, relativePath, maxDepth);
        }

        private void moveHeadOnce() {
            if (moved) {
                return;
            }
            moved = true;
            try {
                GitTestRepositories.writeFile(repository, path, content);
                GitTestRepositories.runGit(repository, "add", "-A");
                GitTestRepositories.commit(repository, "head moved during analysis");
            } catch (Exception e) {
                throw new IllegalStateException("测试无法在分析期间推进 HEAD", e);
            }
        }

        @Override
        public boolean isReadableRepository(WorkspaceRef workspace) {
            return delegate.isReadableRepository(workspace);
        }

        @Override
        public String headRevision(WorkspaceRef workspace) {
            return delegate.headRevision(workspace);
        }

        @Override
        public String readFile(WorkspaceRef workspace, String revision, String relativePath) {
            return delegate.readFile(workspace, revision, relativePath);
        }
    }

    /** AI Gateway 替身：记录收到的请求，返回预设内容或抛出预设失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<AiRequest> requests = new ArrayList<>();

        private String response = "";

        private RuntimeException failure;

        void respond(String rawResponse) {
            this.response = rawResponse;
            this.failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
        }

        AiRequest lastRequest() {
            return requests.get(requests.size() - 1);
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
