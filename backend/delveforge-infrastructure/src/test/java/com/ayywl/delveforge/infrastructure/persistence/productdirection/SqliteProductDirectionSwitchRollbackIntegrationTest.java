package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionStatusConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionTransition;
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
 * <p>因此本类使用自己的临时数据库并且不加 {@code @Transactional}。库里没有清理步骤，
 * 所以每个用例使用**自己的方向标识**，彼此不共享任何一行。
 */
@SpringBootTest(
        classes = SqliteProductDirectionSwitchRollbackIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteProductDirectionSwitchRollbackIntegrationTest {

    private static final Path DATABASE_FILE = Path.of(
            "target", "test-databases", UUID.randomUUID().toString(), "switch-rollback.db");

    private static final ProductDirectionId FIRST_ID = new ProductDirectionId("direction-1");

    private static final ProductDirectionId SECOND_ID = new ProductDirectionId("direction-2");

    private static final ProductDirectionId STALE_SELECTED_ID =
            new ProductDirectionId("direction-stale-1");

    private static final ProductDirectionId STALE_TARGET_ID =
            new ProductDirectionId("direction-stale-2");

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
                () -> repository.saveTransitions(List.of(
                        new ProductDirectionTransition(
                                first, ProductDirectionStatus.CANDIDATE),
                        new ProductDirectionTransition(
                                second, ProductDirectionStatus.CANDIDATE))));

        assertEquals(ProductDirectionStatus.CANDIDATE, statusOf(FIRST_ID),
                "同一批次里后一条失败时，前一条的状态更新必须一并回滚");
        assertEquals(ProductDirectionStatus.CANDIDATE, statusOf(SECOND_ID));
        assertTrue(repository.findCurrentSelected().isEmpty(),
                "库里不得留下任何当前方向");
    }

    /**
     * 批次里**后一条**依据不成立时，前一条已经生效的状态更新必须一并撤销。
     *
     * <p>只断言「失败的那条没写进去」说明不了整批的原子性——真正会留下中间态的是那条
     * 已经生效的前半段。因此这里刻意让第一条能成功、第二条失败。
     *
     * <p>构造的是切换的真实形状：调用方看到的是「STALE_SELECTED 是当前选中、
     * STALE_TARGET 还是候选」，于是在这之后发起切换；而在此期间，另一个请求已经把
     * STALE_TARGET 拒绝了。调用方手上那份「它还是候选」的认知已经作废。
     */
    @Test
    void rollsBackTheFirstTransitionWhenTheSecondBasisIsStale() {
        repository.save(selected(STALE_SELECTED_ID));
        repository.save(direction(STALE_TARGET_ID));

        ProductDirection winner = repository.findById(STALE_TARGET_ID).orElseThrow();
        ProductDirectionStatus winnerBasis = winner.status();
        winner.reject();
        repository.saveTransitions(
                List.of(new ProductDirectionTransition(winner, winnerBasis)));

        ProductDirection stalePrevious = selected(STALE_SELECTED_ID);
        stalePrevious.supersede();
        ProductDirection staleTarget = direction(STALE_TARGET_ID);
        staleTarget.select();

        assertThrows(ProductDirectionStatusConflictException.class,
                () -> repository.saveTransitions(List.of(
                        new ProductDirectionTransition(
                                stalePrevious, ProductDirectionStatus.SELECTED),
                        new ProductDirectionTransition(
                                staleTarget, ProductDirectionStatus.CANDIDATE))));

        assertEquals(ProductDirectionStatus.SELECTED, statusOf(STALE_SELECTED_ID),
                "前一条已经生效的状态更新必须随第二条失败一并撤销");
        assertEquals(ProductDirectionStatus.REJECTED, statusOf(STALE_TARGET_ID),
                "已经提交的拒绝不得被过期副本覆盖为 SELECTED");
    }

    private static ProductDirection selected(ProductDirectionId id) {
        ProductDirection direction = direction(id);
        direction.select();
        return direction;
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
