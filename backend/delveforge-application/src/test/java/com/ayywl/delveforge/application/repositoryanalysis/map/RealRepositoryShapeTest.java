package com.ayywl.delveforge.application.repositoryanalysis.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 在真实验证仓库的完整文件清单上验证分类与路由。
 *
 * <p>下面这份清单是 M1 / M2 端到端验证所用仓库（黑马点评）在
 * {@code analyzedRevision = 18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4} 上的 139 个已提交
 * blob 的快照。它解决的是「启发式只在假想路径上看起来正确」这个问题——
 * M1 的层级筛选就是在替身测试里完全正常、在真实仓库上才暴露的
 * （{@code docs/retrospectives/m1-repository-analysis.md} §5.8）。
 *
 * <p>因此本测试是**故意写死**的：规则变化会让它失败，而失败正是需要被看到的东西。
 *
 * <p>清单里没有文件内容，也没有任何本机路径——只有提交树中的相对路径。
 */
class RealRepositoryShapeTest {

    @Test
    void everyCommittedBlobGetsADescriptorAndExactlyOneLane() {
        List<RepositoryMapEntry> entries = entries();

        assertEquals(139, entries.size());
        assertEquals(139, entries.stream().map(RepositoryMapEntry::reference).distinct().count());
        assertEquals(139, entries.stream().map(RepositoryMapEntry::relativePath).distinct().count());
    }

    /**
     * 三个候选组恰好划分整棵树，没有文件落在分类之外。
     *
     * <p>数量同时是一份基线：真实仓库里业务源码 84 个（80 个 Java + 4 个 Lua）、
     * 基础材料 43 个、当前不参与选取的 12 个（9 个测试类 + 3 个 JMeter 计划）。
     * 任何一条规则的调整都会在这里体现出来——这是刻意的，规则变化应当被看到。
     */
    @Test
    void lanesPartitionTheCompleteTree() {
        RepositoryMap map = map();

        assertEquals(43, map.entriesIn(RepositoryCandidateLane.FOUNDATION).size(),
                "基础材料候选数量变化，请确认是有意调整规则");
        assertEquals(84, map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE).size(),
                "源码候选数量变化，请确认是有意调整规则");
        assertEquals(12, map.entriesIn(RepositoryCandidateLane.NONE).size(),
                "不参与选取的数量变化，请确认是有意调整规则");
        assertEquals(139, map.size());
    }

    /**
     * 本轮重访要解决的问题：业务实现必须进入源码候选。
     *
     * <p>M2 smoke Round 1 记录的正是这些文件一个都没有进入分析的依据
     * （{@code docs/validation/m2-product-direction-discovery-smoke-test-round1.md} §10）。
     * 它们现在全部是 Scout 源码候选，包括当时因超过单文件预算而被整份跳过的
     * {@code MultiLevelCacheServiceImpl.java}。
     */
    @Test
    void businessImplementationReachesTheSourceLane() {
        RepositoryMap map = map();
        List<String> sources = pathsOf(map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE));

        assertTrue(sources.containsAll(List.of(
                "src/main/java/com/hmdp/service/impl/MultiLevelCacheServiceImpl.java",
                "src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java",
                "src/main/java/com/hmdp/service/impl/SeckillOrderTransactionListener.java",
                "src/main/java/com/hmdp/controller/VoucherOrderController.java",
                "src/main/java/com/hmdp/mapper/VoucherMapper.java",
                "src/main/java/com/hmdp/entity/VoucherOrder.java",
                "src/main/java/com/hmdp/mq/SeckillOrderConsumer.java",
                "src/main/java/com/hmdp/job/SeckillReconciliationJob.java")),
                "业务实现必须进入源码候选");
    }

    /**
     * 配置与启动装配类不进源码候选。
     *
     * <p>它们是 M1 分析里唯一稳定可见的那一层，如果继续作为源码候选参与选取，
     * 会把真正需要被看到的业务实现挤出去——那正是本轮要修复的分布问题。
     */
    @Test
    void configAndBootstrapStayOutOfTheSourceLane() {
        RepositoryMap map = map();
        List<String> sources = pathsOf(map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE));

        List<String> configPaths = pathsOf(map.entries()).stream()
                .filter(path -> path.startsWith("src/main/java/com/hmdp/config/"))
                .toList();

        assertEquals(6, configPaths.size());
        for (String path : configPaths) {
            assertFalse(sources.contains(path), "配置类不得进入源码候选: " + path);
        }
        assertFalse(sources.contains("src/main/java/com/hmdp/HmDianPingApplication.java"));
        assertTrue(pathsOf(map.entriesIn(RepositoryCandidateLane.FOUNDATION))
                .containsAll(configPaths));
    }

    /**
     * 测试代码与测试计划不参与选取，但**仍然留在 Map 里**。
     *
     * <p>留在 Map 里是刻意的：分类只改变一个文件进入哪一组，不改变它是否可见。
     * 让文件从 Map 中消失，就等于把 M1 的盲区问题重新引入一次。
     */
    @Test
    void testsAndGeneratedMaterialRemainVisibleButUnselected() {
        RepositoryMap map = map();
        List<String> none = pathsOf(map.entriesIn(RepositoryCandidateLane.NONE));

        assertTrue(none.containsAll(List.of(
                "src/test/java/com/hmdp/cache/CacheEvictionTransactionTest.java",
                "src/test/java/com/hmdp/mq/SeckillOrderConsumerTest.java",
                "jmeter/hmdp_shop.jmx",
                "jmeter/hmdp_seckill.jmx",
                "jmeter/hmdp_null_attack.jmx")));

        // 仍在 Map 中，只是不参与选取
        for (String path : none) {
            assertTrue(pathsOf(map.entries()).contains(path), "文件必须仍然在 Map 中: " + path);
        }
    }

    /**
     * 基础材料覆盖工程元数据、文档、配置、脚本、部署与数据 schema。
     */
    @Test
    void foundationLaneCoversEngineeringMaterial() {
        RepositoryMap map = map();
        List<String> foundation = pathsOf(map.entriesIn(RepositoryCandidateLane.FOUNDATION));

        assertTrue(foundation.containsAll(List.of(
                "pom.xml",
                ".gitignore",
                "docs/7.stress_testing_report_v2.md",
                "docs/seckill-compensation-contract.md",
                "src/main/resources/application.yaml",
                "src/main/resources/mapper/VoucherMapper.xml",
                "src/main/resources/db/hmdp.sql",
                "rocketmq/conf/broker.conf",
                "rocketmq/docker-compose.yml",
                "jmeter/run_v2.bat",
                "jmeter/check_seckill.py",
                "jmeter/round_state_b1_r1.json")));
    }

    /**
     * 一个具体文件的分类结果：从路径到材料类别、语言与角色。
     */
    @Test
    void classifiesRepresentativeRealPaths() {
        assertClassification("pom.xml", RepositoryMaterialKind.BUILD_METADATA,
                RepositoryLanguage.XML);
        assertClassification("src/main/resources/application.yaml",
                RepositoryMaterialKind.CONFIGURATION, RepositoryLanguage.YAML);
        assertClassification("src/main/resources/db/hmdp.sql",
                RepositoryMaterialKind.DATA_SCHEMA, RepositoryLanguage.SQL);
        assertClassification("src/main/resources/mapper/VoucherMapper.xml",
                RepositoryMaterialKind.CONFIGURATION, RepositoryLanguage.XML);
        assertClassification("docs/7.stress_testing_report_v2.md",
                RepositoryMaterialKind.DOCUMENTATION, RepositoryLanguage.MARKDOWN);
        assertClassification("rocketmq/docker-compose.yml",
                RepositoryMaterialKind.DEPLOYMENT, RepositoryLanguage.YAML);
        assertClassification("jmeter/run_v2.bat",
                RepositoryMaterialKind.SCRIPT_AUTOMATION, RepositoryLanguage.SHELL);
        assertClassification("jmeter/setup_tokens.py",
                RepositoryMaterialKind.SCRIPT_AUTOMATION, RepositoryLanguage.PYTHON);
        assertClassification("jmeter/hmdp_shop.jmx",
                RepositoryMaterialKind.TEST_CODE, RepositoryLanguage.UNKNOWN);
        assertClassification("src/main/resources/seckill.lua",
                RepositoryMaterialKind.SOURCE_CODE, RepositoryLanguage.LUA);
        assertClassification("jmeter/shop_ids.csv",
                RepositoryMaterialKind.OTHER, RepositoryLanguage.UNKNOWN);
        assertClassification(".gitignore",
                RepositoryMaterialKind.CONFIGURATION, RepositoryLanguage.UNKNOWN);
    }

    /**
     * 真实仓库里存在一批「看不出角色」的源码目录（{@code event/}、{@code job/}…）。
     *
     * <p>它们带 {@code UNKNOWN} 而不是被猜成某个角色，并且仍然进入源码候选——
     * 否则命名不规范的实现会恰好成为看不见的那一批。
     */
    @Test
    void unknownRoleSourceStaysVisibleInTheSourceLane() {
        RepositoryMap map = map();

        for (String path : List.of(
                "src/main/java/com/hmdp/event/CacheEvictionEvent.java",
                "src/main/java/com/hmdp/event/CacheEvictionListener.java",
                "src/main/java/com/hmdp/job/SeckillReconciliationJob.java",
                "src/main/java/com/hmdp/interceptor/LoginInterceptor.java",
                "src/main/java/com/hmdp/exception/CacheReconstructException.java",
                "src/main/resources/seckill.lua")) {
            RepositoryMapEntry entry = map.find(referenceOf(map, path)).orElseThrow();
            assertEquals(List.of(RepositoryRoleHint.UNKNOWN), entry.roleHints(),
                    "不应为看不出角色的文件编造提示: " + path);
            assertEquals(RepositoryCandidateLane.SCOUT_SOURCE,
                    RepositoryCandidateLane.of(entry), "角色未知的源码仍应可见: " + path);
        }
    }

    // ---------------------------------------------------------------------
    // 验证仓库在 analyzedRevision 上的完整已提交 blob 清单
    // ---------------------------------------------------------------------

    private static final String COMMITTED_TREE = """
            .gitignore
            docs/1.stress_testing_report.md
            docs/10.bugfix-cache-eviction-broadcast.md
            docs/2.resume-interview-prep.md
            docs/3.prompt_for_review.md
            docs/4.prompt_for_review-refactor.md
            docs/5.review-stage1.md
            docs/6.stress_test_plan_v2.md
            docs/7.stress_testing_report_v2.md
            docs/8.stress_report_explained.md
            docs/9.resume-interview-prep-v2.md
            docs/README.md
            docs/cache-review-summary.md
            docs/seckill-compensation-contract.md
            docs/seckill-repair-plan.md
            jmeter/check_seckill.py
            jmeter/count_orders.py
            jmeter/generate_jmeter_files.py
            jmeter/hmdp_null_attack.jmx
            jmeter/hmdp_seckill.jmx
            jmeter/hmdp_shop.jmx
            jmeter/null_shop_ids.csv
            jmeter/reset_seckill.py
            jmeter/round_state_b1_r1.json
            jmeter/round_state_b1_r2.json
            jmeter/round_state_b1_r3.json
            jmeter/round_state_b2_100.json
            jmeter/round_state_b2_1000.json
            jmeter/round_state_b2_500.json
            jmeter/run_v2.bat
            jmeter/setup_tokens.py
            jmeter/shop_ids.csv
            pom.xml
            rocketmq/conf/broker.conf
            rocketmq/conf/proxy.conf
            rocketmq/docker-compose.yml
            src/main/java/com/hmdp/HmDianPingApplication.java
            src/main/java/com/hmdp/config/CaffeineConfig.java
            src/main/java/com/hmdp/config/MvcConfig.java
            src/main/java/com/hmdp/config/MybatisConfig.java
            src/main/java/com/hmdp/config/RedissonConfig.java
            src/main/java/com/hmdp/config/RocketMQConfig.java
            src/main/java/com/hmdp/config/WebExceptionAdvice.java
            src/main/java/com/hmdp/controller/BlogCommentsController.java
            src/main/java/com/hmdp/controller/BlogController.java
            src/main/java/com/hmdp/controller/FollowController.java
            src/main/java/com/hmdp/controller/ShopController.java
            src/main/java/com/hmdp/controller/ShopTypeController.java
            src/main/java/com/hmdp/controller/UploadController.java
            src/main/java/com/hmdp/controller/UserController.java
            src/main/java/com/hmdp/controller/VoucherController.java
            src/main/java/com/hmdp/controller/VoucherOrderController.java
            src/main/java/com/hmdp/dto/CacheEvictionMessage.java
            src/main/java/com/hmdp/dto/LoginFormDTO.java
            src/main/java/com/hmdp/dto/Result.java
            src/main/java/com/hmdp/dto/ScrollResult.java
            src/main/java/com/hmdp/dto/SeckillMessageDTO.java
            src/main/java/com/hmdp/dto/UserDTO.java
            src/main/java/com/hmdp/entity/Blog.java
            src/main/java/com/hmdp/entity/BlogComments.java
            src/main/java/com/hmdp/entity/Follow.java
            src/main/java/com/hmdp/entity/SeckillVoucher.java
            src/main/java/com/hmdp/entity/Shop.java
            src/main/java/com/hmdp/entity/ShopType.java
            src/main/java/com/hmdp/entity/User.java
            src/main/java/com/hmdp/entity/UserInfo.java
            src/main/java/com/hmdp/entity/Voucher.java
            src/main/java/com/hmdp/entity/VoucherOrder.java
            src/main/java/com/hmdp/event/CacheEvictionEvent.java
            src/main/java/com/hmdp/event/CacheEvictionListener.java
            src/main/java/com/hmdp/exception/CacheReconstructException.java
            src/main/java/com/hmdp/interceptor/LoginInterceptor.java
            src/main/java/com/hmdp/interceptor/RefreshTokenExpirationInterceptor.java
            src/main/java/com/hmdp/job/SeckillReconciliationJob.java
            src/main/java/com/hmdp/mapper/BlogCommentsMapper.java
            src/main/java/com/hmdp/mapper/BlogMapper.java
            src/main/java/com/hmdp/mapper/FollowMapper.java
            src/main/java/com/hmdp/mapper/SeckillVoucherMapper.java
            src/main/java/com/hmdp/mapper/ShopMapper.java
            src/main/java/com/hmdp/mapper/ShopTypeMapper.java
            src/main/java/com/hmdp/mapper/UserInfoMapper.java
            src/main/java/com/hmdp/mapper/UserMapper.java
            src/main/java/com/hmdp/mapper/VoucherMapper.java
            src/main/java/com/hmdp/mapper/VoucherOrderMapper.java
            src/main/java/com/hmdp/mq/CacheEvictionConsumer.java
            src/main/java/com/hmdp/mq/CacheEvictionProducer.java
            src/main/java/com/hmdp/mq/SeckillOrderConsumer.java
            src/main/java/com/hmdp/service/IBlogCommentsService.java
            src/main/java/com/hmdp/service/IBlogService.java
            src/main/java/com/hmdp/service/IFollowService.java
            src/main/java/com/hmdp/service/IMultiLevelCacheService.java
            src/main/java/com/hmdp/service/ISeckillVoucherService.java
            src/main/java/com/hmdp/service/IShopService.java
            src/main/java/com/hmdp/service/IShopTypeService.java
            src/main/java/com/hmdp/service/IUserInfoService.java
            src/main/java/com/hmdp/service/IUserService.java
            src/main/java/com/hmdp/service/IVoucherOrderService.java
            src/main/java/com/hmdp/service/IVoucherService.java
            src/main/java/com/hmdp/service/impl/BlogCommentsServiceImpl.java
            src/main/java/com/hmdp/service/impl/BlogServiceImpl.java
            src/main/java/com/hmdp/service/impl/FollowServiceImpl.java
            src/main/java/com/hmdp/service/impl/MultiLevelCacheServiceImpl.java
            src/main/java/com/hmdp/service/impl/SeckillOrderTransactionListener.java
            src/main/java/com/hmdp/service/impl/SeckillVoucherServiceImpl.java
            src/main/java/com/hmdp/service/impl/ShopServiceImpl.java
            src/main/java/com/hmdp/service/impl/ShopTypeServiceImpl.java
            src/main/java/com/hmdp/service/impl/UserInfoServiceImpl.java
            src/main/java/com/hmdp/service/impl/UserServiceImpl.java
            src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
            src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java
            src/main/java/com/hmdp/utils/CacheClientUtils.java
            src/main/java/com/hmdp/utils/GlobalIdGenerator.java
            src/main/java/com/hmdp/utils/ILock.java
            src/main/java/com/hmdp/utils/PasswordEncoder.java
            src/main/java/com/hmdp/utils/RedisConstants.java
            src/main/java/com/hmdp/utils/RedisData.java
            src/main/java/com/hmdp/utils/RegexPatterns.java
            src/main/java/com/hmdp/utils/RegexUtils.java
            src/main/java/com/hmdp/utils/SimpleRedisLock.java
            src/main/java/com/hmdp/utils/SystemConstants.java
            src/main/java/com/hmdp/utils/UserHolder.java
            src/main/java/com/hmdp/vo/ShopCacheVO.java
            src/main/java/com/hmdp/vo/VoucherCacheVO.java
            src/main/resources/application.yaml
            src/main/resources/db/hmdp.sql
            src/main/resources/mapper/VoucherMapper.xml
            src/main/resources/seckill.lua
            src/main/resources/seckill_compensate.lua
            src/main/resources/seckill_compensate_v2.lua
            src/main/resources/unlock.lua
            src/test/java/com/hmdp/HmDianPingApplicationTests.java
            src/test/java/com/hmdp/cache/CacheEvictionFallbackExecutionTest.java
            src/test/java/com/hmdp/cache/CacheEvictionTransactionTest.java
            src/test/java/com/hmdp/mq/CacheEvictionProducerTest.java
            src/test/java/com/hmdp/mq/DedicatedRedisServer.java
            src/test/java/com/hmdp/mq/SeckillCompensateLuaTest.java
            src/test/java/com/hmdp/mq/SeckillOrderConsumerTest.java
            src/test/java/com/hmdp/service/impl/MultiLevelCacheServiceImplLockTest.java
            src/test/java/com/hmdp/service/impl/MultiLevelCacheServiceImplTest.java
            """;

    private static final String REVISION = "18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4";

    private static List<String> committedPaths() {
        List<String> paths = new ArrayList<>();
        for (String line : COMMITTED_TREE.split("\\R")) {
            if (!line.isBlank()) {
                paths.add(line.trim());
            }
        }
        return List.copyOf(paths);
    }

    private static List<RepositoryMapEntry> entries() {
        List<String> paths = committedPaths();
        List<RepositoryMapEntry> entries = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            RepositoryPathClassifier.Classification classification =
                    RepositoryPathClassifier.classify(path);
            entries.add(new RepositoryMapEntry(
                    RepositoryFileReference.of(index + 1),
                    path,
                    1_024,
                    classification.language(),
                    classification.materialKind(),
                    classification.roleHints()));
        }
        return List.copyOf(entries);
    }

    private static RepositoryMap map() {
        return RepositoryMap.of(REVISION, entries());
    }

    private static List<String> pathsOf(List<RepositoryMapEntry> entries) {
        return entries.stream().map(RepositoryMapEntry::relativePath).toList();
    }

    /** 清单里第 i 行的引用就是 {@code RF-i}：编号由位置决定，因此这里可以按位置取。 */
    private static RepositoryFileReference referenceOf(RepositoryMap map, String path) {
        List<String> paths = pathsOf(map.entries());
        int index = paths.indexOf(path);
        if (index < 0) {
            throw new AssertionError("清单中没有这个文件: " + path);
        }
        return RepositoryFileReference.of(index + 1);
    }

    private static void assertClassification(String path, RepositoryMaterialKind kind,
                                             RepositoryLanguage language) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(path);
        assertEquals(kind, classification.materialKind(), "材料类别不符: " + path);
        assertEquals(language, classification.language(), "语言不符: " + path);
    }
}
