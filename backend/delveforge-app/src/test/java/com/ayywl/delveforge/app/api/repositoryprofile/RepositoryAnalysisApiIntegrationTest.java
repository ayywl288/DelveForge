package com.ayywl.delveforge.app.api.repositoryprofile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.app.api.shared.StubWorkspace;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import java.util.ArrayList;
import java.util.List;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 Repository Analysis 的 HTTP 端点：触发分析、读取快照与各类失败的安全响应。
 *
 * <p>使用完整 Spring 上下文与真实 SQLite，Controller、Use Case、Domain 与 Persistence
 * 都是真实实现；只有 Workspace 与 AI 在 Port 边界用替身替代——真实 Git 集成由
 * Infrastructure 的测试覆盖（AGENTS.md §10.3）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class RepositoryAnalysisApiIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final String REVISION = "abc123def456";

    private static final StubWorkspace WORKSPACE = new StubWorkspace();

    private static final StubAiGateway AI_GATEWAY = new StubAiGateway();

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

    @TestConfiguration
    static class StubbedCapabilities {

        @Bean
        @Primary
        AiGateway aiGateway() {
            return AI_GATEWAY;
        }

        @Bean
        @Primary
        WorkspaceReadPort workspaceReadPort() {
            return WORKSPACE;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Scout 响应：三个区域，都指向夹具里唯一的 Java 源码。
     *
     * <p>目录按路径升序是 {@code pom.xml}、{@code src/main/App.java}，
     * 因此源码是 {@code RF-2}。
     */
    private static final String SCOUT_RESPONSE = """
            { "focusAreas": [
                { "label": "入口", "fileRefs": ["RF-2"] },
                { "label": "实现", "fileRefs": ["RF-2"] },
                { "label": "支撑", "fileRefs": ["RF-2"] } ] }
            """;

    @BeforeEach
    void resetStubs() {
        WORKSPACE.reset()
                .givenRevision(REVISION)
                .givenFile("pom.xml", "<project>spring-boot</project>")
                .givenFile("src/main/App.java", "public class App {}");
        // 一次分析调用模型两次：先 Scout，再最终分析
        AI_GATEWAY.respondAll(SCOUT_RESPONSE, PROPOSAL);
    }

    @Test
    void analyzesRegisteredAssetAndReturnsProfile() throws Exception {
        String assetId = registerAsset(true);

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION))
                .andExpect(jsonPath("$.purpose").value("个人记账工具"))
                .andExpect(jsonPath("$.techStack[0]").value("Java 21"))
                .andExpect(jsonPath("$.modules[0]").value("accounting"))
                .andExpect(jsonPath("$.capabilities[0]").value("记账"))
                .andExpect(jsonPath("$.reusableAssets").isEmpty())
                .andExpect(jsonPath("$.limitations").isEmpty())
                .andExpect(jsonPath("$.risks").isEmpty())
                .andExpect(jsonPath("$.evidence[0].sourceType").value("REPOSITORY"))
                .andExpect(jsonPath("$.evidence[0].sourceRef").value("pom.xml"))
                .andExpect(jsonPath("$.evidence[0].claim").value("项目使用 Spring Boot"))
                .andExpect(jsonPath("$.evidence[0].confirmed").value(false))
                .andExpect(jsonPath("$.evidence[0].confidence").doesNotExist());
    }

    /**
     * 日志只记聚合结果，不记文件路径。
     *
     * <p>路径属于用户数据，而异常文本可能嵌入凭据，两者都不进日志
     * （AGENTS.md §8.8）。需要逐条定位时用内存里的诊断，而不是日志。
     */
    @Test
    void logsSafeAggregatesWithoutFilePaths(CapturedOutput output) throws Exception {
        String assetId = registerAsset(true);

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isCreated());

        String aggregateLine = output.getOut().lines()
                .filter(line -> line.contains("operation=repository-analysis"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "应当记录一行聚合日志，实际输出: " + output.getOut()));

        assertTrue(aggregateLine.contains("foundationSelectedCount="),
                "应记录基础材料数量: " + aggregateLine);
        assertTrue(aggregateLine.contains("targetedSourceSelectedCount="),
                "应记录定向源码数量: " + aggregateLine);
        assertTrue(aggregateLine.contains("tooLargeCount=")
                        && aggregateLine.contains("totalBudgetExceededCount="),
                "应记录两类跳过数量: " + aggregateLine);
        assertFalse(aggregateLine.contains("src/main/App.java"),
                "聚合日志不得出现文件路径: " + aggregateLine);
        assertFalse(aggregateLine.contains("pom.xml"),
                "聚合日志不得出现文件路径: " + aggregateLine);
    }

    /**
     * 分析结果会被持久化：按标识查询能读回同一份快照。
     */
    @Test
    void returnsPersistedSnapshotWhenQueried() throws Exception {
        String assetId = registerAsset(true);
        JsonNode analyzed = analyze(assetId);
        String profileId = analyzed.get("id").asText();

        mockMvc.perform(get("/api/repository-profiles/{id}", profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(profileId))
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION))
                .andExpect(jsonPath("$.purpose").value("个人记账工具"))
                .andExpect(jsonPath("$.techStack[0]").value("Java 21"))
                .andExpect(jsonPath("$.evidence[0].sourceRef").value("pom.xml"));
    }

    /**
     * analyzedRevision 与 Evidence 都由服务端产生：请求体里给什么都不生效。
     *
     * <p>该端点不接收请求体，客户端即使发送这些字段也不会被读取。
     */
    @Test
    void ignoresClientSuppliedRevisionAndEvidence() throws Exception {
        String assetId = registerAsset(true);

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "analyzedRevision": "client-chosen-revision",
                                  "purpose": "客户端指定的用途",
                                  "evidence": [
                                    { "sourceType": "USER_INPUT", "sourceRef": "client.txt",
                                      "claim": "客户端指定的依据", "confirmed": true }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION))
                .andExpect(jsonPath("$.purpose").value("个人记账工具"))
                .andExpect(jsonPath("$.evidence[0].sourceRef").value("pom.xml"))
                .andExpect(jsonPath("$.evidence[0].confirmed").value(false));
    }

    @Test
    void returnsNotFoundForUnknownAsset() throws Exception {
        mockMvc.perform(post("/api/software-assets/{id}/analysis", "unknown-asset"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void returnsNotFoundForUnknownProfile() throws Exception {
        mockMvc.perform(get("/api/repository-profiles/{id}", "unknown-profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * 资产自身不允许读取时是 409（INV-A01），不是 400：请求没错，是资产状态不允许。
     */
    @Test
    void rejectsAnalysisWhenReadPermissionIsDenied() throws Exception {
        String assetId = registerAsset(false);

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void rejectsAnalysisWhenLocationIsNotAReadableRepository() throws Exception {
        String assetId = registerAsset(true);
        WORKSPACE.givenNotRepository();

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /**
     * 仓库里没有任何可分析的材料时同样是 409：这不是「请求不合法」，
     * 调用方改请求也解决不了。
     */
    @Test
    void rejectsAnalysisWhenThereIsNoAnalyzableMaterial() throws Exception {
        String assetId = registerAsset(true);
        WORKSPACE.givenNotAnalyzableContent();

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /**
     * 源码目录的形状超出当前分析方式时同样是 409，而不是 500。
     *
     * <p>这里构造的是一个**扁平且过大**的源码集合：几百个源文件直接放在仓库根目录，
     * flat File Catalog 必然超过上限，而它们没有更细的结构可以下钻——分层也降不下去。
     *
     * <p>这类失败以前会掉进「未知服务端故障」，让调用方以为服务坏了、去等重试。
     * 实际上请求可以理解、资产也存在，只是这个仓库当前分析不了，与「没有可分析材料」
     * 是同一类结果，因此必须落成 CONFLICT。
     *
     * <p>安全要求一并验证：响应体只带稳定的错误分类，不含任何内部文本或文件路径。
     */
    @Test
    void rejectsAnalysisWhenTheSourceCatalogShapeExceedsTheCurrentCapability() throws Exception {
        String assetId = registerAsset(true);
        for (int index = 0; index < 800; index++) {
            WORKSPACE.givenFile(String.format("source%04d.java", index), "class Source {}");
        }

        String body = mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("SCOUT_HIERARCHY_GUARD_EXCEEDED"),
                "响应体不得包含内部原因标识: " + body);
        assertFalse(body.contains("source0000.java"),
                "响应体不得包含文件路径: " + body);
    }

    /**
     * 分析失败时返回稳定的错误分类，且不泄漏内部信息。
     */
    @Test
    void returnsSafeErrorWhenAnalysisFails() throws Exception {
        String assetId = registerAsset(true);
        AI_GATEWAY.failWith(new AiGatewayException("api-key=SECRET-TOKEN 调用失败"));

        String body = mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("SECRET-TOKEN"),
                "响应体不得包含外部能力的原始错误文本: " + body);
        assertFalse(body.contains("api-key"),
                "响应体不得包含内部细节: " + body);
    }

    /**
     * 空白标识不会命中资源路由：Spring 会去掉路径段两端的空白，剩下的路径匹配不到任何
     * 处理分支，因此是 404 而不是 400。
     *
     * <p>请求形态本身错误（不合法的 json、未知的枚举取值、违反领域约束的字段）
     * 由注册端点覆盖，那里返回 400。
     */
    @Test
    void rejectsEmptyIdentifierWithoutReachingTheResource() throws Exception {
        mockMvc.perform(get("/api/repository-profiles/%20"))
                .andExpect(status().isNotFound());
    }

    private String registerAsset(boolean readPermissionAllowed) throws Exception {
        String body = mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "location": "E:/projects/legacy-tool",
                                  "readPermissionAllowed": %s,
                                  "usageAuthorization": "UNCLEAR"
                                }
                                """.formatted(readPermissionAllowed)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private JsonNode analyze(String assetId) throws Exception {
        String body = mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    /** AI Gateway 替身：返回预设内容或抛出预设失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<String> responses = new ArrayList<>();

        private RuntimeException failure;

        /** 按调用顺序返回预设内容：先 Scout，再最终分析。 */
        void respondAll(String... rawResponses) {
            responses.clear();
            responses.addAll(List.of(rawResponses));
            failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
            this.responses.clear();
        }

        @Override
        public String generate(AiRequest request) {
            if (failure != null) {
                throw failure;
            }
            if (responses.isEmpty()) {
                throw new AiGatewayException("替身没有更多预设响应");
            }
            return responses.remove(0);
        }
    }
}
