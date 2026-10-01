package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException;
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
 * 验证方向切换的原子性：一个批次里让两条方向都进入 {@code SELECTED} 时，两条都不生效。
 *
 * <p>一次真实的切换是 {@code [原方向 → SUPERSEDED, 目标方向 → SELECTED]}。它之所以不会
 * 撞上 INV-D09 的存储层守卫，正是因为原方向在同一个批次里先离开了 {@code SELECTED}——
 * 顺序是语义的一部分，不是实现细节。
 *
 * <p>反过来，一个批次里两条都要进入 {@code SELECTED} 就是非法的：第一条写成功之后，
 * 第二条会撞上 V7 的部分唯一索引。此时**第一条的状态更新必须一并回滚**，
 * 否则库里会留下一个「已经离开候选、但没有变成选中」的中间态。
 *
 * <h2>为什么单独一个类、且刻意不加 {@code @Transactional}</h2>
 *
 * <p>与 {@code SqliteProductDirectionBatchRollbackIntegrationTest} 同样的理由：回滚只有在
 * 事务<b>真正结束</b>之后才发生。测试方法自己开着事务时，{@code saveAll} 会加入它，
 * 异常只是把它标记为 rollback-only，已写入的改动在方法内部依然读得到——
 * 那样写出来的断言测不到回滚，只是绕过了它。
 *
 * <p>因此本类只放这一个用例，并使用自己的临时数据库：没有别的用例会被它影响，
 * 它也不需要清理。
 */
@SpringBootTest(
        classes = SqliteProductDirectionSwitchRollbackIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteProductDirectionSwitchRollbackIntegrationTest {

    private static final Path DATABASE_FILE = Path.of(
            "target", "test-databases", UUID.randomUUID().toString(), "switch-rollback.db");

    private static final ProductDirectionId FIRST_ID = new ProductDirectionId("direction-1");

    private static final ProductDirectionId SECOND_ID = new ProductDirectionId("direction-2");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final Evidence EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-profile-1#interests", "用户长期关注记账工具",
            0.8, true);

    private static final DirectionEvidenceSupport SUPPORT = new DirectionEvidenceSupport(
            List.of(new EvidenceBasis(EVIDENCE,
                    new UserProfileEvidenceOrigin(USER_PROFILE_ID, 3))),
            List.of(), List.of());

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

    @Test
    void rollsBackTheWholeSwitchWhenTheSecondDirectionCannotBecomeSelected() {
        repository.saveAll(List.of(direction(FIRST_ID), direction(SECOND_ID)));

        ProductDirection first = direction(FIRST_ID);
        first.select();
        ProductDirection second = direction(SECOND_ID);
        second.select();

        assertThrows(ProductDirectionSelectionConflictException.class,
                () -> repository.saveAll(List.of(first, second)));

        assertEquals(ProductDirectionStatus.CANDIDATE, statusOf(FIRST_ID),
                "同一批次里后一条失败时，前一条的状态更新必须一并回滚");
        assertEquals(ProductDirectionStatus.CANDIDATE, statusOf(SECOND_ID));
        assertTrue(repository.findCurrentSelected().isEmpty(),
                "库里不得留下任何当前方向");
    }

    private ProductDirectionStatus statusOf(ProductDirectionId id) {
        return repository.findById(id).orElseThrow().status();
    }

    private static ProductDirection direction(ProductDirectionId id) {
        return ProductDirection.create(
                id, USER_PROFILE_ID, 3,
                List.of(REPOSITORY_PROFILE_ID),
                "个人记账 + 报表导出", "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出", "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID), "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力", "中等：主要在导出与模板部分",
                List.of(), SUPPORT);
    }
}
