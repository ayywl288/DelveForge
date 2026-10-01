package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
 * 验证真实写锁竞争被翻译成可分类的冲突，而不是未分类的数据访问异常。
 *
 * <p>SQLite 在存在并发写入者时直接拒绝后来者，而不是排队等待：等不到锁时抛
 * {@code SQLITE_BUSY}。这与唯一约束冲突是两回事，但含义与处置相同——本次写入无法确认
 * 自己所依据的状态仍然成立。不翻译它，接口层的并发败方会拿到 500，而不是 409。
 *
 * <h2>为什么单独一个类、且刻意不加 {@code @Transactional}</h2>
 *
 * <p>竞争必须在**真实的两条连接**之间发生，而测试方法自己开着事务时，所有操作都挤在
 * 一条连接上，写锁从来不会落到别人手里——那样写出来的用例测不到竞争本身。
 * 这里用一条独立的连接持有一个未提交的写事务，再由 Repository 从连接池取另一条连接去写。
 *
 * <p>本类只放这一个用例，并使用自己的临时数据库。
 */
@SpringBootTest(
        classes = SqliteProductDirectionWriteContentionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteProductDirectionWriteContentionIntegrationTest {

    private static final Path DATABASE_FILE = Path.of(
            "target", "test-databases", UUID.randomUUID().toString(), "write-contention.db");

    private static final ProductDirectionId DIRECTION_ID = new ProductDirectionId("direction-1");

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

    @Autowired
    private DataSource dataSource;

    @Test
    void translatesWriteLockContentionIntoAConflict() throws Exception {
        repository.save(direction());

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            // 持有写锁但一直不提交：另一条连接上的写入会等不到它。
            try (Statement statement = blocker.createStatement()) {
                statement.executeUpdate(
                        "UPDATE product_direction SET status = 'CANDIDATE' WHERE id = '"
                                + DIRECTION_ID.value() + "'");
            }

            ProductDirection loaded = repository.findById(DIRECTION_ID).orElseThrow();
            ProductDirectionStatus basis = loaded.status();
            loaded.select();

            assertThrows(ProductDirectionStatusConflictException.class,
                    () -> repository.saveTransitions(
                            List.of(new ProductDirectionTransition(loaded, basis))),
                    "写锁竞争必须翻译成项目自己的冲突类型，而不是未分类的数据访问异常");

            blocker.rollback();
        }

        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.findById(DIRECTION_ID).orElseThrow().status(),
                "竞争失败的一方不得留下任何状态变化");
    }

    private static ProductDirection direction() {
        return ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, 3,
                List.of(REPOSITORY_PROFILE_ID),
                "个人记账 + 报表导出", "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出", "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID), "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力", "中等：主要在导出与模板部分",
                List.of(), SUPPORT);
    }
}
