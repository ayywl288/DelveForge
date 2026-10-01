package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryMaterialBudget;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadExecutor;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadPlanner;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证理解链路：Map → Scout → 规划 → 读取，以及它的流程前置条件。
 *
 * <p>AI 与 Workspace 都在 Port 边界用替身替代（AGENTS.md §10.2、§10.6），
 * 因此不依赖 Spring 容器、不访问网络、不读取真实文件系统。
 */
class RepositoryUnderstandingTest {

    private static final WorkspaceRef WORKSPACE = new WorkspaceRef("E:/projects/demo");

    private static final String REVISION = "aaaa1111";

    private static final String POM = "pom.xml";

    private static final String APP = "src/main/App.java";

    private static final String OTHER = "src/main/Other.java";

    /** 目录按路径升序：RF-1 = pom.xml，RF-2 = App.java，RF-3 = Other.java。 */
    private static final String SCOUT_RESPONSE = """
            { "focusAreas": [
                { "label": "入口", "fileRefs": ["RF-2"] },
                { "label": "其余", "fileRefs": ["RF-3"] },
                { "label": "再来一次", "fileRefs": ["RF-2"] } ] }
            """;

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final UnderstandingWorkspace workspace = new UnderstandingWorkspace();

    @Test
    void readsMaterialForThePlannedFiles() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);

        List<RepositorySourceFile> material = understanding().understand(WORKSPACE, REVISION);

        assertTrue(pathsOf(material).contains(APP), "Scout 指出的源码应当被读进来: " + pathsOf(material));
        assertTrue(pathsOf(material).contains(POM), "基础材料应当被读进来: " + pathsOf(material));
        assertEquals(1, aiGateway.callCount(), "理解阶段只调用一次模型——就是 Scout");
    }

    /**
     * Scout 拿到的是**全部**源码候选，而不是某一批。
     */
    @Test
    void scoutReceivesEveryScoutSourceDescriptor() {
        workspace.given(POM, "<project/>");
        workspace.given(APP, "class App {}");
        workspace.given(OTHER, "class Other {}");
        aiGateway.respondAll(SCOUT_RESPONSE);

        understanding().understand(WORKSPACE, REVISION);

        String catalog = aiGateway.lastRequest().messages().get(1).content();
        assertTrue(catalog.contains(APP), "源码候选必须全部进入 Scout 目录");
        assertTrue(catalog.contains(OTHER));
        assertTrue(!catalog.contains(POM), "基础材料不走 Scout 这条通道");
    }

    /**
     * 同一个 revision 从头用到尾：建 Map、读取都用它，而且**不解析 HEAD**。
     *
     * <p>替身在这三件事上会直接失败，因此本用例同时证明理解链路没有自己去找 revision。
     */
    @Test
    void usesTheGivenRevisionForEveryOperation() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);

        understanding().understand(WORKSPACE, REVISION);

        assertEquals(List.of(REVISION), workspace.listedRevisions());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION::equals),
                "所有读取都必须带着同一个 revision: " + workspace.readRevisions());
        assertEquals(0, workspace.headRevisionCalls());
    }

    // ---------------------------------------------------------------------
    // 前置条件：都在调用模型之前失败
    // ---------------------------------------------------------------------

    /**
     * 没有源码候选就直接失败，不进行一次注定失败的调用。
     */
    @Test
    void rejectsARepositoryWithoutSourceCandidatesBeforeCallingTheModel() {
        workspace.given(POM, "<project/>");
        workspace.given("README.md", "# demo");

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> understanding().understand(WORKSPACE, REVISION));

        assertEquals(0, aiGateway.callCount(), "没有源码候选时不调用 Scout");
        assertTrue(workspace.readPaths().isEmpty(), "也不读取任何文件");
    }

    /**
     * 目录载荷超出上限时失败关闭，并且**不调用模型**。
     *
     * <p>不截断、不采样：截断会让 Scout 在不知情的情况下少看一部分候选，
     * 而那是「分析结果为什么漏了这些文件」最难查的一类原因。
     */
    @Test
    void rejectsACatalogThatExceedsTheLimitBeforeCallingTheModel() {
        seedSources();

        RepositoryNotAnalyzableException failure = assertThrows(
                RepositoryNotAnalyzableException.class,
                () -> understandingWithCatalogLimit(16).understand(WORKSPACE, REVISION));

        assertTrue(failure.getMessage().contains(RepositoryUnderstanding.SCOUT_CATALOG_TOO_LARGE),
                "失败原因应当带上稳定的标识: " + failure.getMessage());
        assertEquals(0, aiGateway.callCount(), "目录超限时不调用 Scout");
        assertTrue(workspace.readPaths().isEmpty());
    }

    /**
     * 计划一个都没选中时直接失败，不去读取，也不调用分析模型。
     */
    @Test
    void rejectsAnEmptyReadPlan() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);

        // 两条通道的单文件上限都小于任何一个候选，因此规划阶段全部被跳过
        RepositoryMaterialBudget tiny = new RepositoryMaterialBudget(10, 1, 1);
        RepositoryUnderstanding understanding = new RepositoryUnderstanding(
                new RepositoryMapBuilder(workspace),
                new RepositoryScoutExtraction(aiGateway, new ObjectMapper()),
                new RepositoryReadPlanner(tiny, tiny),
                new RepositoryReadExecutor(workspace, tiny, tiny),
                65_536);

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> understanding.understand(WORKSPACE, REVISION));

        assertEquals(1, aiGateway.callCount(), "Scout 已经调用过了，但不会再调用分析模型");
        assertTrue(workspace.readPaths().isEmpty(), "计划为空时不读取任何文件");
    }

    /**
     * 计划选中了文件，但实际内容在执行期全部被剔除时同样失败。
     *
     * <p>这里让 Workspace 报出的 blob 尺寸小于真实内容——正是「规划依据 metadata、
     * 而 metadata 不一定等于真实内容」这一情形。
     */
    @Test
    void rejectsEmptyMaterialAfterExecutionTimeChecks() {
        workspace.given(POM, "<project/>");
        workspace.given(APP, "class App {}");
        workspace.given(OTHER, "class Other {}");
        workspace.givenReportedSize(POM, 1);
        workspace.givenReportedSize(APP, 1);
        workspace.givenReportedSize(OTHER, 1);
        aiGateway.respondAll(SCOUT_RESPONSE);

        RepositoryUnderstanding understanding = understandingWithBudgets(
                new RepositoryMaterialBudget(10, 5, 100),
                new RepositoryMaterialBudget(10, 5, 100));

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> understanding.understand(WORKSPACE, REVISION));
    }

    // ---------------------------------------------------------------------
    // 失败传播
    // ---------------------------------------------------------------------

    @Test
    void propagatesScoutFailure() {
        seedSources();
        aiGateway.failOnCall(1, new AiGatewayException("模型不可用"));

        assertThrows(AiGatewayException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
        assertTrue(workspace.readPaths().isEmpty(), "Scout 失败时不读取任何文件");
    }

    @Test
    void propagatesMalformedScoutOutput() {
        seedSources();
        aiGateway.respondAll("{}");

        assertThrows(AiGatewayException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
    }

    /**
     * Scout 引用了本次没有提供的编号时整次失败，不做部分补救。
     */
    @Test
    void propagatesReferenceValidationFailure() {
        seedSources();
        aiGateway.respondAll("""
                { "focusAreas": [
                    { "label": "a", "fileRefs": ["RF-99"] },
                    { "label": "b", "fileRefs": ["RF-2"] },
                    { "label": "c", "fileRefs": ["RF-3"] } ] }
                """);

        assertThrows(AiGatewayException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
    }

    @Test
    void propagatesReadFailure() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);
        workspace.failReadsWith(new WorkspaceException("读取失败"));

        assertThrows(WorkspaceException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private void seedSources() {
        workspace.given(POM, "<project/>");
        workspace.given(APP, "class App {}");
        workspace.given(OTHER, "class Other {}");
    }

    private RepositoryUnderstanding understanding() {
        return understandingWithBudgets(
                new RepositoryMaterialBudget(10, 1_000, 10_000),
                new RepositoryMaterialBudget(10, 1_000, 10_000));
    }

    private RepositoryUnderstanding understandingWithCatalogLimit(int maxCatalogBytes) {
        return new RepositoryUnderstanding(
                new RepositoryMapBuilder(workspace),
                new RepositoryScoutExtraction(aiGateway, new ObjectMapper()),
                new RepositoryReadPlanner(
                        new RepositoryMaterialBudget(10, 1_000, 10_000),
                        new RepositoryMaterialBudget(10, 1_000, 10_000)),
                new RepositoryReadExecutor(
                        workspace,
                        new RepositoryMaterialBudget(10, 1_000, 10_000),
                        new RepositoryMaterialBudget(10, 1_000, 10_000)),
                maxCatalogBytes);
    }

    private RepositoryUnderstanding understandingWithBudgets(
            RepositoryMaterialBudget foundation, RepositoryMaterialBudget targetedSource) {
        return new RepositoryUnderstanding(
                new RepositoryMapBuilder(workspace),
                new RepositoryScoutExtraction(aiGateway, new ObjectMapper()),
                new RepositoryReadPlanner(foundation, targetedSource),
                new RepositoryReadExecutor(workspace, foundation, targetedSource),
                65_536);
    }

    private static List<String> pathsOf(List<RepositorySourceFile> material) {
        return material.stream().map(RepositorySourceFile::relativePath).toList();
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

    /**
     * 提供列目录与读取的替身。
     *
     * <p>解析 HEAD 会直接失败：理解链路的 revision 必须由调用方给出。
     */
    private static final class UnderstandingWorkspace implements WorkspaceReadPort {

        private final Map<String, String> files = new LinkedHashMap<>();

        private final Map<String, Long> reportedSizes = new LinkedHashMap<>();

        private final List<String> listedRevisions = new ArrayList<>();

        private final List<String> readRevisions = new ArrayList<>();

        private final List<String> readPaths = new ArrayList<>();

        private RuntimeException failure;

        void given(String relativePath, String content) {
            files.put(relativePath, content);
        }

        /** 让列目录报出一个与真实内容不同的尺寸，用来模拟 metadata 与内容不一致。 */
        void givenReportedSize(String relativePath, long size) {
            reportedSizes.put(relativePath, size);
        }

        void failReadsWith(RuntimeException exception) {
            this.failure = exception;
        }

        List<String> listedRevisions() {
            return List.copyOf(listedRevisions);
        }

        List<String> readRevisions() {
            return List.copyOf(readRevisions);
        }

        List<String> readPaths() {
            return List.copyOf(readPaths);
        }

        int headRevisionCalls() {
            return 0;
        }

        @Override
        public boolean isReadableRepository(WorkspaceRef workspace) {
            throw new AssertionError("理解链路不判断仓库可读性");
        }

        @Override
        public List<WorkspaceEntry> listEntries(
                WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
            listedRevisions.add(revision);
            List<WorkspaceEntry> entries = new ArrayList<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                long size = reportedSizes.getOrDefault(
                        file.getKey(),
                        (long) file.getValue().getBytes(StandardCharsets.UTF_8).length);
                entries.add(new WorkspaceEntry(file.getKey(), false, size));
            }
            return List.copyOf(entries);
        }

        @Override
        public String readFile(WorkspaceRef workspace, String revision, String relativePath) {
            readRevisions.add(revision);
            readPaths.add(relativePath);
            if (failure != null) {
                throw failure;
            }
            String content = files.get(relativePath);
            if (content == null) {
                throw new WorkspaceException("文件不存在: " + relativePath);
            }
            return content;
        }

        @Override
        public String headRevision(WorkspaceRef workspace) {
            throw new AssertionError("理解链路不得解析 HEAD");
        }
    }
}
