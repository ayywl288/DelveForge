package com.ayywl.delveforge.app.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
    void resetStubs() {
        WORKSPACE.reset()
                .givenRevision(REVISION)
                .givenFile("pom.xml", "<project>spring-boot</project>")
                .givenFile("src/main/App.java", "public class App {}");
        AI_GATEWAY.respond(PROPOSAL);
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

        @Override
        public String generate(AiRequest request) {
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
