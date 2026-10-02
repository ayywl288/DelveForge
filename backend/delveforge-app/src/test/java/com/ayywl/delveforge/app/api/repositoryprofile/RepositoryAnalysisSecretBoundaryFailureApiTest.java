package com.ayywl.delveforge.app.api.repositoryprofile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.app.api.shared.StubWorkspace;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretBoundaryException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.SanitizedRepositoryMaterial;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
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
 * 凭据边界**自身失败**时的对外语义（ADR-0006 §失败语义）。
 *
 * <pre>
 * 边界失败 → 整次分析失败 → 不调用最终分析、不写快照
 *          → 对外是「当前无法分析」（409），不是「服务内部错误」（500）
 * </pre>
 *
 * <p>它单独成一个上下文，因为这里要用一个**必定失败**的政策替身替换生产政策；
 * 其它 API 用例需要的是真实政策。把两者放进同一个上下文只能靠可变开关，
 * 那会让「生产装配里跑的到底是哪一条政策」变得需要读测试才知道。
 *
 * <p>安全断言是本文的重点：响应体里既不能出现内部原因标识，也不能出现任何路径或内容。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RepositoryAnalysisSecretBoundaryFailureApiTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final String REVISION = "abc123def456";

    private static final StubWorkspace WORKSPACE = new StubWorkspace();

    private static final StubAiGateway AI_GATEWAY = new StubAiGateway();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    /**
     * 用必定失败的政策替换生产政策。
     *
     * <p>{@code excludes} 正常放行，失败发生在**内容净化**这一步——也就是第二个执行点。
     */
    @TestConfiguration
    static class FailingSecretBoundary {

        @Bean
        @Primary
        RepositorySecretPolicy failingRepositorySecretPolicy() {
            return new RepositorySecretPolicy() {

                @Override
                public boolean excludes(String relativePath) {
                    return false;
                }

                @Override
                public SanitizedRepositoryMaterial sanitize(List<RepositorySourceFile> files) {
                    throw new RepositorySecretBoundaryException(
                            "净化无法完成（替身）CANARY-BOUNDARY-DETAIL-0001");
                }
            };
        }

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
        AI_GATEWAY.respondAll("""
                { "focusAreas": [
                    { "label": "入口", "fileRefs": ["RF-2"] },
                    { "label": "实现", "fileRefs": ["RF-2"] },
                    { "label": "支撑", "fileRefs": ["RF-2"] } ] }
                """);
    }

    @Test
    void reportsBoundaryFailureAsNotAnalyzableWithoutLeakingAnything() throws Exception {
        String assetId = registerAsset();

        String body = mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("SECRET_BOUNDARY_FAILED"),
                "响应体不得包含内部原因标识: " + body);
        assertFalse(body.contains("CANARY-BOUNDARY-DETAIL-0001"),
                "响应体不得包含边界抛出的文本: " + body);
        assertFalse(body.contains("canary"), "响应体不得包含任何金丝雀");
        assertFalse(body.contains("pom.xml"), "响应体不得包含文件路径: " + body);
        assertFalse(body.contains("App.java"), "响应体不得包含文件路径: " + body);
        assertTrue(body.contains("CONFLICT"), "只表达「当前无法分析」这一件事");
    }

    private String registerAsset() throws Exception {
        String body = mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "location": "E:/projects/legacy-tool",
                                  "readPermissionAllowed": true,
                                  "usageAuthorization": "UNCLEAR"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /** AI Gateway 替身：按调用顺序返回预设内容。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<String> responses = new java.util.ArrayList<>();

        void respondAll(String... rawResponses) {
            responses.clear();
            responses.addAll(List.of(rawResponses));
        }

        @Override
        public String generate(AiRequest request) {
            if (responses.isEmpty()) {
                throw new AiGatewayException("替身没有更多预设响应");
            }
            return responses.remove(0);
        }
    }
}
