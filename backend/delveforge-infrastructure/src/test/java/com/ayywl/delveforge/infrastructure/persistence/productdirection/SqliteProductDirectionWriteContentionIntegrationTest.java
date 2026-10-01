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
 * <p>两个用例各自使用**不同的方向标识**：库里没有清理步骤，彼此不共享任何一行。
 *
 * <p>第二个用例比第一个更接近真实：它阻塞的是提交而不是语句。
 */
@SpringBootTest(
        classes = SqliteProductDirectionWriteContentionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteProductDirectionWriteContentionIntegrationTest {

    private static final Path DATABASE_FILE = Path.of(
            "target", "test-databases", UUID.randomUUID().toString(), "write-contention.db");

    private static final ProductDirectionId DIRECTION_ID = new ProductDirectionId("direction-1");

    private static final ProductDirectionId COMMIT_PHASE_ID =
            new ProductDirectionId("direction-commit-phase");

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
        repository.save(direction(DIRECTION_ID));

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

    /**
     * 提交阶段的锁竞争。
     *
     * <p>与上面那条不同：这里阻塞的不是语句，而是**提交**。SQLite 的写入语句只需要
     * RESERVED 锁，它可以和别的连接的读锁共存；但提交需要 EXCLUSIVE 锁，任何一个没结束的
     * 读事务都会把它挡在门外。因此「每条语句都成功、提交却失败」是真实存在的一种结果。
     *
     * <p>只覆盖语句阶段的翻译是发现不了这一条的：写语句会照常成功，事务在方法返回之后
     * 才提交，那时已经不在这层代码里了。
     *
     * <p>同时验证失败之后连接是可用的：SQLite 的提交失败不会让事务自己结束，连接会带着
     * 一个未结束的事务回到池子里，之后借到它的操作会继续失败。因此这里在竞争结束之后，
     * 不再借新连接池，直接用同一个池做一次读写，确认它已经恢复正常。
     */
    @Test
    void translatesCommitPhaseContentionIntoAConflict() throws Exception {
        repository.save(direction(COMMIT_PHASE_ID));

        try (Connection reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            // 持有一个未结束的读事务：提交需要独占锁，它会把提交挡住。
            try (Statement statement = reader.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM product_direction")) {
                rows.next();
            }

            ProductDirection loaded = repository.findById(COMMIT_PHASE_ID).orElseThrow();
            ProductDirectionStatus basis = loaded.status();
            loaded.select();

            assertThrows(ProductDirectionStatusConflictException.class,
                    () -> repository.saveTransitions(
                            List.of(new ProductDirectionTransition(loaded, basis))),
                    "提交阶段的锁竞争必须翻译成项目自己的冲突类型，而不是 500");

            reader.rollback();
        }

        // 竞争结束之后，同一个连接池必须立刻可用：状态没被改写，读写都正常。
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.findById(COMMIT_PHASE_ID).orElseThrow().status(),
                "提交失败的一次写入不得留下任何状态变化");

        ProductDirection again = repository.findById(COMMIT_PHASE_ID).orElseThrow();
        ProductDirectionStatus againBasis = again.status();
        again.select();
        repository.saveTransitions(List.of(new ProductDirectionTransition(again, againBasis)));

        assertEquals(ProductDirectionStatus.SELECTED,
                repository.findById(COMMIT_PHASE_ID).orElseThrow().status(),
                "失败之后连接必须已经清理干净，后续写入应当正常");
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
