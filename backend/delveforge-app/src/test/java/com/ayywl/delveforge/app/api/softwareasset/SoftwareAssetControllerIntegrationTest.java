package com.ayywl.delveforge.app.api.softwareasset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.app.api.shared.StubWorkspace;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.infrastructure.persistence.softwareasset.SoftwareAssetMapper;
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
 * 验证 Software Asset 注册与查询端点。
 *
 * <p>使用完整 Spring 上下文与真实 SQLite（临时数据库），因此覆盖 Controller 映射、
 * Use Case 与 Persistence Adapter 的整条链路。外部能力在 Port 边界用替身替代
 * （AGENTS.md §10.2）：这里不调用真实 LLM，也不访问真实 Git 仓库。
 *
 * <p>{@code @Transactional} 使每个测试方法结束后回滚，保证方法之间状态隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SoftwareAssetControllerIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final StubWorkspace WORKSPACE = new StubWorkspace();

    private static final StubAiGateway AI_GATEWAY = new StubAiGateway();

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

    @Autowired
    private SoftwareAssetMapper softwareAssetMapper;

    @BeforeEach
    void resetStubs() {
        WORKSPACE.reset().givenFile("README.md", "# legacy tool");
        AI_GATEWAY.respond("{}");
    }

    @Test
    void registersLocalGitRepositoryAsset() throws Exception {
        mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("E:/projects/legacy-tool", true, "MIT", "UNCLEAR")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("GIT_REPOSITORY"))
                .andExpect(jsonPath("$.source").value("USER_SPECIFIED"))
                .andExpect(jsonPath("$.location").value("E:/projects/legacy-tool"))
                .andExpect(jsonPath("$.readPermissionAllowed").value(true))
                .andExpect(jsonPath("$.licenseInfo").value("MIT"))
                .andExpect(jsonPath("$.usageAuthorization").value("UNCLEAR"));
    }

    /**
     * 登记后可以按标识读回：两次请求都在同一条真实链路上。
     */
    @Test
    void returnsRegisteredAsset() throws Exception {
        String id = register("E:/projects/legacy-tool", true, null, "UNCLEAR");

        mockMvc.perform(get("/api/software-assets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.location").value("E:/projects/legacy-tool"))
                .andExpect(jsonPath("$.type").value("GIT_REPOSITORY"))
                .andExpect(jsonPath("$.licenseInfo").doesNotExist());
    }

    @Test
    void returnsNotFoundForUnknownAsset() throws Exception {
        mockMvc.perform(get("/api/software-assets/{id}", "unknown-asset"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * 读取权限必须显式给出。
     *
     * <p>字段缺失或为 null 时，原始类型会把「没给」静默变成 false，等于替用户声明了一个
     * 「不允许读取」的授权事实（DOMAIN_MODEL.md §3.2、RULE-DOM-004）。因此这里拒绝，
     * 并且不写入任何资产。
     */
    @Test
    void rejectsRegistrationWithoutReadPermission() throws Exception {
        for (String body : List.of(
                """
                { "location": "E:/projects/legacy-tool", "usageAuthorization": "UNCLEAR" }
                """,
                """
                { "location": "E:/projects/legacy-tool", "readPermissionAllowed": null,
                  "usageAuthorization": "UNCLEAR" }
                """)) {
            mockMvc.perform(post("/api/software-assets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        assertEquals(0, softwareAssetMapper.selectCount(null).intValue(),
                "被拒绝的注册不得写入资产");
    }

    /**
     * 使用授权只能按取值名称给出：数字与数字字符串不是这个 API 的契约。
     *
     * <p>Jackson 默认允许用数字表示枚举（按序号），那样 {@code 0} 会被静默当成
     * 第一个取值——凭空制造出一个授权事实。
     */
    @Test
    void rejectsNumericUsageAuthorization() throws Exception {
        mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBodyWithUsage("0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBodyWithUsage("\"0\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertEquals(0, softwareAssetMapper.selectCount(null).intValue(),
                "被拒绝的注册不得写入资产");
    }

    /**
     * 标识、类型与来源由服务端决定：客户端即使在请求体里给出这些字段也不会生效。
     */
    @Test
    void ignoresClientSuppliedIdentityAndType() throws Exception {
        mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "client-chosen-id",
                                  "type": "SOMETHING_ELSE",
                                  "source": "CLIENT_CHOSEN",
                                  "location": "E:/projects/legacy-tool",
                                  "readPermissionAllowed": true,
                                  "usageAuthorization": "UNCLEAR"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not("client-chosen-id")))
                .andExpect(jsonPath("$.type").value("GIT_REPOSITORY"))
                .andExpect(jsonPath("$.source").value("USER_SPECIFIED"));
    }

    @Test
    void rejectsMalformedRegistrationRequest() throws Exception {
        // 未知的使用授权取值：反序列化即失败
        mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("E:/projects/legacy-tool", true, null, "SOMETHING")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // location 为空：由 Domain 判定，同样是 400
        mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("  ", true, null, "UNCLEAR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 请求体不是合法 json
        mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    private String register(String location, boolean readPermission, String license, String usage)
            throws Exception {
        String body = mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody(location, readPermission, license, usage)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private static String registrationBody(
            String location, boolean readPermission, String license, String usage) {
        return """
                {
                  "location": %s,
                  "readPermissionAllowed": %s,
                  "licenseInfo": %s,
                  "usageAuthorization": "%s"
                }
                """.formatted(
                json(location), readPermission, json(license), usage);
    }

    /** 只替换 usageAuthorization 取值形式的注册请求体：{@code raw} 是原始 json 片段。 */
    private static String registrationBodyWithUsage(String raw) {
        return """
                {
                  "location": "E:/projects/legacy-tool",
                  "readPermissionAllowed": true,
                  "usageAuthorization": %s
                }
                """.formatted(raw);
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** AI Gateway 替身：记录请求并返回预设内容。 */
    private static final class StubAiGateway implements AiGateway {

        private String response = "{}";

        void respond(String rawResponse) {
            this.response = rawResponse;
        }

        @Override
        public String generate(AiRequest request) {
            return response;
        }
    }
}
