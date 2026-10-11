package com.ayywl.delveforge.app.api.repositoryprofile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
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
 * 用真实 Workspace Adapter 走一遍 HTTP 回归：位置不可用的资产不能被当成「请求不合法」。
 *
 * <p>本类只替换 AI（它是真正的外部能力），Workspace 用真实的 {@code GitWorkspaceAdapter}。
 * 这些场景不需要真实 Git 仓库——它们验证的正是「位置本身不可用」时 HTTP 的语义。
 *
 * <p>分析已有资产的请求没有位置参数，位置不可用仍为 409。新登记已拒绝相对路径，
 * 但历史相对位置不能被删除或默认变得可分析；以旧记录夹具保留这条真实 Workspace 回归。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RepositoryAnalysisRealWorkspaceHttpIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final StubAiGateway AI_GATEWAY = new StubAiGateway();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @TestConfiguration
    static class StubbedAiOnly {

        @Bean
        @Primary
        AiGateway aiGateway() {
            return AI_GATEWAY;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SoftwareAssetRepository assets;

    /**
     * 旧相对路径记录仍会在分析时被真实 Workspace 拒绝，不调用 AI。
     */
    @Test
    void reportsLegacyRelativeLocationAsNotAnalyzable() throws Exception {
        String assetId = "legacy-relative";
        assets.save(SoftwareAsset.create(new SoftwareAssetId(assetId), SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "relative/repo", true, null, UsageAuthorization.UNCLEAR));

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /**
     * 不存在的绝对路径：同样不是「请求写错了」，而是这个资产当前分析不了。
     */
    @Test
    void reportsMissingLocationAsNotAnalyzable() throws Exception {
        String assetId = registerAsset(
                Path.of("target", "does-not-exist", UUID.randomUUID().toString())
                        .toAbsolutePath().toString());

        mockMvc.perform(post("/api/software-assets/{id}/analysis", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    private String registerAsset(String location) throws Exception {
        String body = mockMvc.perform(post("/api/software-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "location": %s,
                                  "readPermissionAllowed": true,
                                  "usageAuthorization": "UNCLEAR"
                                }
                                """.formatted(objectMapper.writeValueAsString(location))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /** AI Gateway 替身：本类的场景都在调用 AI 之前失败。 */
    private static final class StubAiGateway implements AiGateway {

        @Override
        public String generate(AiRequest request) {
            throw new AssertionError("位置不可用时不应调用 AI");
        }
    }
}
