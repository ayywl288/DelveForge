package com.ayywl.delveforge.application.repositoryanalysis.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证候选路由：分类描述文件，路由决定它将来可能怎么被选中。
 *
 * <p>本测试同时是「分类与路由是两个独立问题」这一划分的检查点：路由只读
 * {@code materialKind} 与 {@code roleHints}，不重复实现路径判断。
 */
class RepositoryCandidateLaneTest {

    // ---------------------------------------------------------------------
    // Foundation 候选
    // ---------------------------------------------------------------------

    @Test
    void routesNonSourceMaterialsToFoundation() {
        assertLane(RepositoryCandidateLane.FOUNDATION, "pom.xml");
        assertLane(RepositoryCandidateLane.FOUNDATION, "docs/7.stress_testing_report_v2.md");
        assertLane(RepositoryCandidateLane.FOUNDATION, "src/main/resources/application.yaml");
        assertLane(RepositoryCandidateLane.FOUNDATION, "jmeter/run_v2.bat");
        assertLane(RepositoryCandidateLane.FOUNDATION, "rocketmq/docker-compose.yml");
        assertLane(RepositoryCandidateLane.FOUNDATION, "src/main/resources/db/hmdp.sql");
        assertLane(RepositoryCandidateLane.FOUNDATION, "jmeter/shop_ids.csv");
    }

    /**
     * 源码中的配置与启动装配类进 Foundation，而不是源码候选。
     *
     * <p>它们回答的是「这个应用怎么被装配起来」，属于建立工程认识的那一组；
     * 而且真实验证已经证明它们在源码里占比很高，放进源码候选会明显稀释后者。
     */
    @Test
    void routesConfigBootstrapSourceToFoundation() {
        assertLane(RepositoryCandidateLane.FOUNDATION,
                "src/main/java/com/hmdp/config/CaffeineConfig.java");
        assertLane(RepositoryCandidateLane.FOUNDATION,
                "src/main/java/com/hmdp/HmDianPingApplication.java");
    }

    // ---------------------------------------------------------------------
    // Scout 源码候选
    // ---------------------------------------------------------------------

    @Test
    void routesBusinessSourceToScoutSource() {
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/hmdp/controller/ShopController.java");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/hmdp/service/impl/ShopServiceImpl.java");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/hmdp/entity/Shop.java");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/hmdp/mapper/ShopMapper.java");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/hmdp/mq/CacheEvictionProducer.java");
    }

    /**
     * 角色未知的源码仍然进源码候选。
     *
     * <p>这是 M1 教训的直接体现：如果「看不出角色」就意味着不可见，那么看不见的文件
     * 恰好会是那些命名不规范的实现——它们往往才是真正需要被看一眼的东西。
     * 拿不准就交给后续阶段去判断，而不是在这里悄悄丢掉。
     */
    @Test
    void routesUnknownRoleSourceToScoutSource() {
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/hmdp/event/CacheEvictionEvent.java");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "internal/handler/shop.go");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "web/src/components/ShopList.vue");
    }

    /**
     * 配置与业务两个信号同时存在时，按「角色集合恰好是 CONFIG_BOOTSTRAP」判断。
     *
     * <p>一个既在 {@code config/} 下、类名又以 {@code Service} 结尾的类更像业务代码，
     * 因此进源码候选。判据是整个集合，而不是「是否包含 CONFIG_BOOTSTRAP」——
     * 后者会让这种情形同时满足两条规则，无从判断。
     */
    @Test
    void routesSourceWithConfigAndBusinessSignalsToScoutSource() {
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/hmdp/config/ShopConfigService.java");
    }

    // ---------------------------------------------------------------------
    // 不进入任何一组
    // ---------------------------------------------------------------------

    /**
     * 测试代码与生成物不进任何一组。
     *
     * <p>它们仍然在 Map 里——Map 表示完整的已提交树——只是当前不作为候选参与选取。
     */
    @Test
    void routesTestsAndGeneratedMaterialToNone() {
        assertLane(RepositoryCandidateLane.NONE,
                "src/test/java/com/hmdp/cache/CacheEvictionTransactionTest.java");
        assertLane(RepositoryCandidateLane.NONE, "jmeter/hmdp_shop.jmx");
        assertLane(RepositoryCandidateLane.NONE, "node_modules/left-pad/index.js");
        assertLane(RepositoryCandidateLane.NONE, "target/classes/com/hmdp/Shop.class");
        assertLane(RepositoryCandidateLane.NONE, "package-lock.json");
    }

    /**
     * 生成物与依赖绝不被当作业务源码候选——这是本测试最重要的一条。
     */
    @Test
    void neverTreatsGeneratedOrVendorPathsAsBusinessSource() {
        String[] generated = {
                "node_modules/left-pad/index.js",
                "vendor/github.com/x/y.go",
                "target/classes/com/hmdp/Shop.class",
                "build/generated/Sources.java",
                "dist/bundle.js",
                "public/app.min.js",
                "package-lock.json",
        };
        for (String path : generated) {
            assertEquals(RepositoryCandidateLane.NONE, laneOf(path),
                    "生成物或依赖不得成为任何候选: " + path);
        }
    }

    /**
     * 回归：命名像生成物、或结尾像测试的文件，最终 lane 必须仍然可被选中。
     *
     * <p>这两类误排除的后果不是「归类不准」，而是文件在 Foundation 与 Scout 两组里都无法
     * 被选中——与 M1 的盲区同一种失效方式。因此这里直接断言最终 lane，而不是只看分类。
     */
    @Test
    void neverExcludesBusinessCodeBecauseOfItsName() {
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/acme/build/BuildService.java");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE,
                "src/main/java/com/acme/vendor/VendorService.java");
        assertLane(RepositoryCandidateLane.SCOUT_SOURCE, "src/components/SpeedTest.vue");
        assertLane(RepositoryCandidateLane.FOUNDATION, "docs/LoadTest.md");
    }

    @Test
    void everyEntryBelongsToExactlyOneLane() {
        String[] paths = {
                "pom.xml",
                "src/main/java/com/hmdp/config/MvcConfig.java",
                "src/main/java/com/hmdp/service/impl/ShopServiceImpl.java",
                "src/test/java/com/hmdp/ShopTest.java",
                "node_modules/a.js",
        };
        for (String path : paths) {
            // 路由是全覆盖的：任何描述符都能得到一个确定的组
            RepositoryCandidateLane lane = laneOf(path);
            assertEquals(lane, RepositoryCandidateLane.of(entryFor(path)));
        }
    }

    @Test
    void rejectsNullEntry() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryCandidateLane.of(null));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private static void assertLane(RepositoryCandidateLane expected, String path) {
        assertEquals(expected, laneOf(path), "候选组不符: " + path);
    }

    private static RepositoryCandidateLane laneOf(String path) {
        return RepositoryCandidateLane.of(entryFor(path));
    }

    private static RepositoryMapEntry entryFor(String path) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(path);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(1),
                path,
                1_024,
                classification.language(),
                classification.materialKind(),
                classification.roleHints());
    }
}
