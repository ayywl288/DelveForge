package com.ayywl.delveforge.app.api.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证**提交阶段**的锁竞争在选择端点上解析为 409，而不是 500。
 *
 * <p>与 {@code ProductDirectionSelectionApiIntegrationTest} 里的并发用例不同：那一条
 * 模拟的是「读到的当前方向已经不是最新的」，走的是条件更新的判断；这一条让写语句全部成功、
 * 只在提交时失败——SQLite 的提交需要独占锁，一个未结束的读事务就足以把它挡住。
 *
 * <p>这一条曾经返回 500：提交发生在 Repository 方法返回之后，那时已经不在任何能翻译
 * 失败的地方，而且默认的事务管理器在提交失败后不回滚，连接会带着未结束的事务回到池子里。
 *
 * <h2>为什么刻意不加 {@code @Transactional}</h2>
 *
 * <p>测试方法自己开着事务时，请求里的写入会加入它，提交被推迟到测试结束——
 * 那样根本走不到提交阶段，也就测不到这条路径。这里让写入真实提交，也让失败真实发生。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductDirectionCommitContentionApiIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "commit-contention.db");

    private static final ProductDirectionId DIRECTION_ID = new ProductDirectionId("direction-1");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int USER_PROFILE_REVISION = 3;

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final Evidence EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-profile-1#interests", "用户长期关注记账工具",
            0.8, true);

    private static final DirectionEvidenceSupport SUPPORT = new DirectionEvidenceSupport(
            List.of(new EvidenceBasis(EVIDENCE,
                    new UserProfileEvidenceOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION))),
            List.of(), List.of());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductDirectionRepository productDirectionRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void returnsConflictWhenTheCommitCannotTakeTheWriteLock() throws Exception {
        productDirectionRepository.save(direction());

        String body;
        try (Connection reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            // 一个未结束的读事务：写语句仍然可以成功，提交却拿不到独占锁。
            try (Statement statement = reader.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM product_direction")) {
                rows.next();
            }

            body = mockMvc.perform(post("/api/product-directions/{id}/select", DIRECTION_ID.value()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            reader.rollback();
        }

        assertFalse(
                body.contains("SQLITE") || body.contains("BUSY") || body.contains("ux_product_direction"),
                "响应不得泄漏存储层细节: " + body);

        assertEquals(
                ProductDirectionStatus.CANDIDATE,
                productDirectionRepository.findById(DIRECTION_ID).orElseThrow().status(),
                "提交失败的那次选择不得留下任何状态变化");

        // 连接必须已经清理干净：同一个连接池立刻可以继续服务。
        mockMvc.perform(post("/api/product-directions/{id}/select", DIRECTION_ID.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SELECTED"));
    }

    private static ProductDirection direction() {
        return ProductDirection.create(
                DIRECTION_ID,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                "个人记账 + 报表导出",
                "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                SUPPORT);
    }
}
