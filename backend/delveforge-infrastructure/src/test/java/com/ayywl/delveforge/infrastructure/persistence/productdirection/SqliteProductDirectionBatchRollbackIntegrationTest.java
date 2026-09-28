package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionContentConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 验证整批写入的原子性：批里任意一条失败时，整批都不留下。
 *
 * <p>这正是 {@link ProductDirectionRepository#saveAll} 存在的理由。逐条保存会让先写入的
 * 那几条留在库里，留下一批「只出现了一部分」的候选——用户看到的就不再是模型这次发现的
 * 东西。
 *
 * <h2>为什么单独一个类、且刻意不加 {@code @Transactional}</h2>
 *
 * <p>回滚只有在事务<b>真正结束</b>之后才发生。若测试方法自己开着事务，{@code saveAll}
 * 会加入它，异常只是把它标记为 rollback-only，已写入的行在方法内部依然读得到——
 * 那样写出来的断言测不到回滚本身，只是绕过了它。
 *
 * <p>因此这里让写入真实提交，也让失败真实回滚。为了仍然保持隔离，本类只放这一个用例，
 * 并使用自己的临时数据库：没有别的用例会被它影响，它也不需要清理。
 */
@SpringBootTest(
        classes = SqliteProductDirectionBatchRollbackIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteProductDirectionBatchRollbackIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "batch-rollback.db");

    private static final ProductDirectionId DIRECTION_ID = new ProductDirectionId("direction-1");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final Evidence USER_EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "用户输入：导出报表很麻烦", "用户对报表导出的不满",
            0.8, true);

    private static final Evidence REPOSITORY_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/report", "已有报表渲染模块", null, false);

    private static final DirectionEvidenceSupport SUPPORT = new DirectionEvidenceSupport(
            List.of(new EvidenceBasis(USER_EVIDENCE,
                    new UserProfileEvidenceOrigin(USER_PROFILE_ID, 3))),
            List.of(new EvidenceBasis(USER_EVIDENCE,
                    new UserProfileEvidenceOrigin(USER_PROFILE_ID, 3))),
            List.of(new EvidenceBasis(REPOSITORY_EVIDENCE,
                    new RepositoryProfileEvidenceOrigin(REPOSITORY_PROFILE_ID))));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteProductDirectionRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.productdirection")
    static class TestApplication {
    }

    @Autowired
    private ProductDirectionRepository repository;

    @Autowired
    private ProductDirectionMapper directionMapper;

    @Test
    void rollsBackTheWholeBatchWhenOneDirectionFails() {
        ProductDirection fresh = direction("第一个方向");
        ProductDirection conflicting = directionWithSameIdButDifferentContent();

        assertThrows(ProductDirectionContentConflictException.class,
                () -> repository.saveAll(List.of(fresh, conflicting)));

        assertTrue(repository.findById(DIRECTION_ID).isEmpty(),
                "整批回滚：同标识的那条冲突了，先写入的这条也必须消失");
        assertEquals(0, directionMapper.selectCount(null).intValue(), "库里不留下任何方向");
    }

    private static ProductDirection direction(String title) {
        return ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, 3,
                List.of(REPOSITORY_PROFILE_ID),
                title, "现有记账工具缺少可导出的报表", "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配", List.of(ASSET_ID),
                "相比现有工具增加了自定义报表", "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分", List.of(), SUPPORT);
    }

    /** 标识与 {@link #direction} 相同、内容不同：批内第二条必然撞上内容冲突。 */
    private static ProductDirection directionWithSameIdButDifferentContent() {
        return ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, 3,
                List.of(REPOSITORY_PROFILE_ID),
                "换成另一个方向", "另一个问题", "另一个目标产品", "另一个匹配点",
                List.of(ASSET_ID), "另一个差异化", "另一个技术价值", "另一个复杂度",
                List.of(), SUPPORT);
    }
}
