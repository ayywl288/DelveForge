package com.ayywl.delveforge.application.repositoryanalysis.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证确定性分类：材料类别、语言与结构角色提示。
 *
 * <p>断言的是**路径 → 结论**这一映射本身，因此每个用例都写死路径与期望值。
 * 规则变化时这些用例会失败——这是刻意的：分类规则是 Map 质量的全部来源，
 * 它不应该在无人察觉的情况下漂移。
 */
class RepositoryPathClassifierTest {

    // ---------------------------------------------------------------------
    // Java 源码：结构角色
    // ---------------------------------------------------------------------

    @Test
    void classifiesJavaApiEntryByPathAndByClassName() {
        assertRole("src/main/java/com/hmdp/controller/ShopController.java",
                RepositoryRoleHint.API_ENTRY);
        assertRole("src/main/java/com/hmdp/api/ShopApi.java",
                RepositoryRoleHint.API_ENTRY);
        assertRole("src/main/java/com/hmdp/rest/ShopEndpoint.java",
                RepositoryRoleHint.API_ENTRY);
    }

    @Test
    void classifiesJavaApplicationServiceByPathAndByClassName() {
        assertRole("src/main/java/com/hmdp/service/IShopService.java",
                RepositoryRoleHint.APPLICATION_SERVICE);
        assertRole("src/main/java/com/hmdp/service/impl/ShopServiceImpl.java",
                RepositoryRoleHint.APPLICATION_SERVICE);
        assertRole("src/main/java/com/hmdp/application/PlaceOrderUseCase.java",
                RepositoryRoleHint.APPLICATION_SERVICE);
    }

    @Test
    void classifiesJavaDomainModelByPathAndByClassName() {
        assertRole("src/main/java/com/hmdp/entity/Shop.java",
                RepositoryRoleHint.DOMAIN_MODEL);
        assertRole("src/main/java/com/hmdp/domain/ShopAggregate.java",
                RepositoryRoleHint.DOMAIN_MODEL);
        assertRole("src/main/java/com/hmdp/dto/UserDTO.java",
                RepositoryRoleHint.DOMAIN_MODEL);
        assertRole("src/main/java/com/hmdp/vo/ShopCacheVO.java",
                RepositoryRoleHint.DOMAIN_MODEL);
    }

    @Test
    void classifiesJavaPersistenceByPathAndByClassName() {
        assertRole("src/main/java/com/hmdp/mapper/ShopMapper.java",
                RepositoryRoleHint.PERSISTENCE);
        assertRole("src/main/java/com/hmdp/repository/ShopRepository.java",
                RepositoryRoleHint.PERSISTENCE);
        assertRole("src/main/java/com/hmdp/dao/ShopDao.java",
                RepositoryRoleHint.PERSISTENCE);
        assertRole("src/main/java/com/hmdp/persistence/ShopAccess.java",
                RepositoryRoleHint.PERSISTENCE);
    }

    @Test
    void classifiesJavaIntegrationByPathAndByClassName() {
        assertRole("src/main/java/com/hmdp/mq/CacheEvictionProducer.java",
                RepositoryRoleHint.INTEGRATION);
        assertRole("src/main/java/com/hmdp/client/PaymentClient.java",
                RepositoryRoleHint.INTEGRATION);
        assertRole("src/main/java/com/hmdp/gateway/SmsGateway.java",
                RepositoryRoleHint.INTEGRATION);
    }

    @Test
    void classifiesJavaConfigBootstrapByPathAndByClassName() {
        assertRole("src/main/java/com/hmdp/config/CaffeineConfig.java",
                RepositoryRoleHint.CONFIG_BOOTSTRAP);
        assertRole("src/main/java/com/hmdp/HmDianPingApplication.java",
                RepositoryRoleHint.CONFIG_BOOTSTRAP);
        assertRole("src/main/java/com/hmdp/boot/AppAutoConfiguration.java",
                RepositoryRoleHint.CONFIG_BOOTSTRAP);
    }

    @Test
    void classifiesJavaUtilityByPathAndByClassName() {
        assertRole("src/main/java/com/hmdp/utils/RedisConstants.java",
                RepositoryRoleHint.UTILITY);
        assertRole("src/main/java/com/hmdp/support/IdHelper.java",
                RepositoryRoleHint.UTILITY);
    }

    /**
     * 一个信号不足以判断时，允许同时带多个角色提示。
     *
     * <p>一个既位于 {@code config/} 下、类名又以 {@code Service} 结尾的类，两个信号都真实
     * 存在，因此两个提示都保留——由候选路由决定它更像哪一种（见
     * {@link RepositoryCandidateLane#of}）。
     */
    @Test
    void keepsEveryRoleHintThatHasARealSignal() {
        assertEquals(
                List.of(RepositoryRoleHint.APPLICATION_SERVICE, RepositoryRoleHint.PERSISTENCE),
                RepositoryPathClassifier.classify(
                        "src/main/java/com/hmdp/service/OrderRepository.java").roleHints());

        assertEquals(
                List.of(RepositoryRoleHint.API_ENTRY, RepositoryRoleHint.UTILITY),
                RepositoryPathClassifier.classify(
                        "src/main/java/com/hmdp/controller/ShopUtils.java").roleHints());

        // 顺序是枚举声明顺序，不是发现顺序：APPLICATION_SERVICE 声明在 CONFIG_BOOTSTRAP 之前
        assertEquals(
                List.of(RepositoryRoleHint.APPLICATION_SERVICE,
                        RepositoryRoleHint.CONFIG_BOOTSTRAP),
                RepositoryPathClassifier.classify(
                        "src/main/java/com/hmdp/config/ShopConfigService.java").roleHints());
    }

    // ---------------------------------------------------------------------
    // 源码：不编造角色
    // ---------------------------------------------------------------------

    /**
     * 看不出角色的源码带 {@code UNKNOWN}，而不是被猜成某个具体角色。
     *
     * <p>{@code event/}、{@code interceptor/}、{@code job/} 都是真实仓库里存在、但在当前
     * 提示集里没有对应角色的目录。它们仍然是源码，只是角色未知。
     */
    @Test
    void unknownRoleRatherThanInventingOne() {
        assertRole("src/main/java/com/hmdp/event/CacheEvictionEvent.java",
                RepositoryRoleHint.UNKNOWN);
        assertRole("src/main/java/com/hmdp/interceptor/LoginInterceptor.java",
                RepositoryRoleHint.UNKNOWN);
        assertRole("src/main/java/com/hmdp/job/SeckillReconciliationJob.java",
                RepositoryRoleHint.UNKNOWN);
    }

    /**
     * 非 Java 源码同样可表示：语言能识别，角色按路径段（语言无关）判断，
     * 判断不出就是 {@code UNKNOWN}。
     */
    @Test
    void representsNonJavaSourcesWithoutFabricatedRoles() {
        assertEquals(RepositoryMaterialKind.SOURCE_CODE,
                RepositoryPathClassifier.classify("app/services/shop_service.py")
                        .materialKind());
        assertEquals(RepositoryLanguage.PYTHON,
                RepositoryPathClassifier.classify("app/services/shop_service.py").language());
        assertEquals(List.of(RepositoryRoleHint.APPLICATION_SERVICE),
                RepositoryPathClassifier.classify("app/services/shop_service.py").roleHints());

        assertEquals(RepositoryLanguage.GO,
                RepositoryPathClassifier.classify("internal/handler/shop.go").language());
        assertEquals(List.of(RepositoryRoleHint.UNKNOWN),
                RepositoryPathClassifier.classify("internal/handler/shop.go").roleHints());

        assertEquals(RepositoryLanguage.VUE,
                RepositoryPathClassifier.classify("web/src/components/ShopList.vue").language());
        assertEquals(List.of(RepositoryRoleHint.UNKNOWN),
                RepositoryPathClassifier.classify("web/src/components/ShopList.vue").roleHints());
    }

    /**
     * {@code test_} 前缀之外的 Python 测试命名也是测试代码。
     */
    @Test
    void recognisesPythonTestsByConvention() {
        assertEquals(RepositoryMaterialKind.TEST_CODE,
                RepositoryPathClassifier.classify("app/test_shop.py").materialKind());
        assertEquals(RepositoryMaterialKind.TEST_CODE,
                RepositoryPathClassifier.classify("app/shop_test.py").materialKind());
    }

    /**
     * Go 的标准测试命名 {@code *_test.go} 是测试代码。
     *
     * <p>它由 {@code go test} 自己识别，是 Go 唯一的测试文件惯例（没有 {@code test/} 目录约定、
     * 也没有 {@code *Test.go} 之类）。不识别的后果在真实仓库上已经看到：
     * memos 的 {@code acl_config_test.go} / {@code authz_test.go} 以 {@code SOURCE_CODE} 进入
     * SCOUT_SOURCE，占用了本该给出产品实现的候选位。
     */
    @Test
    void recognisesGoTestsByConvention() {
        assertKind(RepositoryMaterialKind.TEST_CODE, "store/store_test.go");
        assertKind(RepositoryMaterialKind.TEST_CODE, "internal/acl/acl_config_test.go");
        assertKind(RepositoryMaterialKind.TEST_CODE, "authz_test.go");
    }

    /**
     * Go 的测试惯例**只**认 {@code _test.go} 后缀。
     *
     * <p>Go 里没有 Java 那种「类名以 Test 结尾」的约定，因此
     * {@code TestUtils.go}、{@code contest.go}、{@code latest.go} 都是普通源码——
     * 把它们判成测试会让正常代码从所有候选组里消失。
     */
    @Test
    void doesNotApplyTestNamingToOrdinaryGoFiles() {
        assertKind(RepositoryMaterialKind.SOURCE_CODE, "internal/contest/contest.go");
        assertKind(RepositoryMaterialKind.SOURCE_CODE, "pkg/TestUtils.go");
        assertKind(RepositoryMaterialKind.SOURCE_CODE, "pkg/latest.go");
        assertKind(RepositoryMaterialKind.SOURCE_CODE, "pkg/testing_helpers.go");
    }

    // ---------------------------------------------------------------------
    // 回归：生成目录名不得把正常业务包一起排掉
    // ---------------------------------------------------------------------

    /**
     * {@code build} / {@code vendor} 是构建输出目录名，也是完全正常的业务包名。
     *
     * <p>只看目录名会把「构建管理」「供应商管理」这类业务实现整体判成生成物，
     * 于是它们在 Foundation 与 Scout 两组里都无法被选中——这恰好是 M1 层级筛选制造
     * 盲区的同一个错误，只是换了个粒度。因此这类名字只在**源码树之外**才作数。
     */
    @Test
    void doesNotTreatBusinessPackagesNamedLikeBuildOutputAsGenerated() {
        assertKind(RepositoryMaterialKind.SOURCE_CODE,
                "src/main/java/com/acme/build/BuildService.java");
        assertKind(RepositoryMaterialKind.SOURCE_CODE,
                "src/main/java/com/acme/vendor/VendorService.java");
        assertKind(RepositoryMaterialKind.SOURCE_CODE,
                "src/main/java/com/acme/dist/DistService.java");
        assertKind(RepositoryMaterialKind.SOURCE_CODE,
                "src/main/java/com/acme/out/OutputService.java");
        assertKind(RepositoryMaterialKind.SOURCE_CODE,
                "src/main/java/com/acme/coverage/CoverageReportService.java");
        assertKind(RepositoryMaterialKind.SOURCE_CODE,
                "app/internal/build/BuildCoordinator.kt");
    }

    /**
     * 源码树之外的构建输出与依赖目录仍然是生成物——修复不能把这条一起丢掉。
     */
    @Test
    void stillTreatsRealBuildOutputAsGenerated() {
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "target/classes/com/acme/Shop.class");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "build/libs/app.jar");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "dist/bundle.js");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "out/production/Shop.class");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "coverage/lcov.info");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "vendor/github.com/x/y.go");
        // 不可能同时是包名的结构性目录，任何层级都成立
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "node_modules/left-pad/index.js");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "src/main/webapp/node_modules/a.js");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "__pycache__/shop.cpython-311.pyc");
    }

    // ---------------------------------------------------------------------
    // Java 测试代码：大小写敏感的命名判断
    // ---------------------------------------------------------------------

    @Test
    void classifiesJavaTestsByPathAndByClassName() {
        assertEquals(RepositoryMaterialKind.TEST_CODE,
                RepositoryPathClassifier.classify(
                        "src/test/java/com/hmdp/cache/CacheEvictionTransactionTest.java")
                        .materialKind());
        assertEquals(RepositoryMaterialKind.TEST_CODE,
                RepositoryPathClassifier.classify(
                        "src/main/java/com/hmdp/ShopServiceTest.java").materialKind());
        assertEquals(RepositoryMaterialKind.TEST_CODE,
                RepositoryPathClassifier.classify(
                        "src/main/java/com/hmdp/ShopServiceIT.java").materialKind());
    }

    /**
     * {@code *Test} 是 Java 的类名惯例，不是「名字里出现 Test 就是测试」。
     *
     * <p>把这条惯例套到所有文件上，会让一个测速产品的组件（{@code SpeedTest.vue}）
     * 或一份压测说明（{@code LoadTest.md}）被判成测试代码，从而退出所有候选组。
     * 每条测试命名规则只作用于它真正适用的文件类型。
     */
    @Test
    void doesNotApplyJavaTestNamingToOtherFileTypes() {
        assertKind(RepositoryMaterialKind.SOURCE_CODE, "src/components/SpeedTest.vue");
        assertKind(RepositoryMaterialKind.SOURCE_CODE, "web/src/views/SpeedTest.tsx");
        assertKind(RepositoryMaterialKind.SOURCE_CODE, "internal/load/LoadTest.go");
        assertKind(RepositoryMaterialKind.DOCUMENTATION, "docs/LoadTest.md");
        assertKind(RepositoryMaterialKind.CONFIGURATION, "config/Test.java.properties");
        assertKind(RepositoryMaterialKind.TEST_CODE, "src/components/SpeedTest.spec.ts");
    }

    /**
     * 测试目录同样只对源码文件成立。
     *
     * <p>{@code src/test} 下的一个 fixture 是测试**资源**，不是测试代码。
     */
    @Test
    void appliesTestDirectoriesOnlyToSourceFiles() {
        assertKind(RepositoryMaterialKind.TEST_CODE,
                "src/test/java/com/hmdp/ShopServiceTest.java");
        assertKind(RepositoryMaterialKind.CONFIGURATION,
                "src/test/resources/application-test.yaml");
        assertKind(RepositoryMaterialKind.OTHER, "src/test/resources/orders.csv");
    }

    /**
     * 小写比较会把 {@code Audit.java} 误判成 {@code *IT.java}。
     *
     * <p>这是一个真实存在的命名陷阱：{@code "audit.java".endsWith("it.java")} 为真。
     * Java 的测试类命名约定是大小写敏感的（{@code FooTest} / {@code FooIT}），
     * 因此这里也按大小写敏感匹配。
     */
    @Test
    void doesNotMistakeAuditForAnIntegrationTest() {
        assertEquals(RepositoryMaterialKind.SOURCE_CODE,
                RepositoryPathClassifier.classify(
                        "src/main/java/com/hmdp/audit/Audit.java").materialKind());
        assertEquals(RepositoryMaterialKind.SOURCE_CODE,
                RepositoryPathClassifier.classify(
                        "src/main/java/com/hmdp/audit/AuditLog.java").materialKind());
    }

    // ---------------------------------------------------------------------
    // 非源码材料
    // ---------------------------------------------------------------------

    @Test
    void classifiesBuildMetadata() {
        assertKind(RepositoryMaterialKind.BUILD_METADATA, "pom.xml");
        assertKind(RepositoryMaterialKind.BUILD_METADATA, "build.gradle.kts");
        assertKind(RepositoryMaterialKind.BUILD_METADATA, "package.json");
        assertKind(RepositoryMaterialKind.BUILD_METADATA, "go.mod");
        assertKind(RepositoryMaterialKind.BUILD_METADATA, "Makefile");
    }

    @Test
    void classifiesDocumentation() {
        assertKind(RepositoryMaterialKind.DOCUMENTATION, "docs/7.stress_testing_report_v2.md");
        assertKind(RepositoryMaterialKind.DOCUMENTATION, "README.md");
        assertKind(RepositoryMaterialKind.DOCUMENTATION, "LICENSE");
        assertKind(RepositoryMaterialKind.DOCUMENTATION, "docs/notes.txt");
    }

    @Test
    void classifiesConfiguration() {
        assertKind(RepositoryMaterialKind.CONFIGURATION, "src/main/resources/application.yaml");
        assertKind(RepositoryMaterialKind.CONFIGURATION, "rocketmq/conf/broker.conf");
        assertKind(RepositoryMaterialKind.CONFIGURATION, "src/main/resources/mapper/VoucherMapper.xml");
        assertKind(RepositoryMaterialKind.CONFIGURATION, ".gitignore");
    }

    @Test
    void classifiesScriptAutomation() {
        assertKind(RepositoryMaterialKind.SCRIPT_AUTOMATION, "jmeter/run_v2.bat");
        assertKind(RepositoryMaterialKind.SCRIPT_AUTOMATION, "scripts/deploy.sh");
        assertKind(RepositoryMaterialKind.SCRIPT_AUTOMATION, "tools/rotate.ps1");
        // 工具目录下的 Python：是自动化脚本，不是业务源码
        assertKind(RepositoryMaterialKind.SCRIPT_AUTOMATION, "jmeter/setup_tokens.py");
        assertEquals(RepositoryLanguage.PYTHON,
                RepositoryPathClassifier.classify("jmeter/setup_tokens.py").language());
    }

    @Test
    void classifiesDeployment() {
        assertKind(RepositoryMaterialKind.DEPLOYMENT, "rocketmq/docker-compose.yml");
        assertKind(RepositoryMaterialKind.DEPLOYMENT, "Dockerfile");
        assertKind(RepositoryMaterialKind.DEPLOYMENT, "deploy/k8s/service.yaml");
        assertKind(RepositoryMaterialKind.DEPLOYMENT, "infra/main.tf");
    }

    @Test
    void classifiesDataSchema() {
        assertKind(RepositoryMaterialKind.DATA_SCHEMA, "src/main/resources/db/hmdp.sql");
        assertKind(RepositoryMaterialKind.DATA_SCHEMA, "db/migration/V1__init.sql");
        assertEquals(RepositoryLanguage.SQL,
                RepositoryPathClassifier.classify("src/main/resources/db/hmdp.sql").language());
    }

    @Test
    void classifiesTestDefinitions() {
        assertKind(RepositoryMaterialKind.TEST_CODE, "jmeter/hmdp_shop.jmx");
    }

    @Test
    void classifiesEverythingElseAsOther() {
        assertKind(RepositoryMaterialKind.OTHER, "jmeter/shop_ids.csv");
        assertKind(RepositoryMaterialKind.OTHER, "assets/logo.bin");
    }

    // ---------------------------------------------------------------------
    // 生成物与依赖
    // ---------------------------------------------------------------------

    /**
     * 生成物与依赖仍然有描述符（Map 表示完整的已提交树），只是不会被当作业务源码候选。
     */
    @Test
    void classifiesGeneratedAndVendorMaterial() {
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "target/classes/com/hmdp/Shop.class");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "node_modules/left-pad/index.js");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "vendor/github.com/x/y.go");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "package-lock.json");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "yarn.lock");
        assertKind(RepositoryMaterialKind.GENERATED_VENDOR, "public/app.min.js");
    }

    // ---------------------------------------------------------------------
    // 副作用与边界
    // ---------------------------------------------------------------------

    @Test
    void isPureAndDeterministic() {
        String path = "src/main/java/com/hmdp/service/impl/ShopServiceImpl.java";
        assertEquals(RepositoryPathClassifier.classify(path),
                RepositoryPathClassifier.classify(path));
    }

    @Test
    void rejectsBlankPath() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryPathClassifier.classify(null));
        assertThrows(IllegalArgumentException.class, () -> RepositoryPathClassifier.classify("  "));
    }

    /**
     * 分类结果不可被外部改动。
     */
    @Test
    void classificationIsImmutable() {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify("src/main/java/com/hmdp/controller/ShopController.java");
        assertThrows(UnsupportedOperationException.class,
                () -> classification.roleHints().add(RepositoryRoleHint.UNKNOWN));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private static void assertKind(RepositoryMaterialKind expected, String path) {
        assertEquals(expected, RepositoryPathClassifier.classify(path).materialKind(),
                "材料类别不符: " + path);
    }

    private static void assertRole(String path, RepositoryRoleHint... expected) {
        assertEquals(List.of(expected), RepositoryPathClassifier.classify(path).roleHints(),
                "结构角色不符: " + path);
        assertEquals(RepositoryMaterialKind.SOURCE_CODE,
                RepositoryPathClassifier.classify(path).materialKind(),
                "应当是源码: " + path);
    }
}
