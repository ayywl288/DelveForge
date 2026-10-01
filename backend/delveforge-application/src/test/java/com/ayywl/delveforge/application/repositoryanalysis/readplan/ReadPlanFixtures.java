package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryPathClassifier;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionArea;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionPlan;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取规划测试共用的 Map 与查看计划。
 *
 * <p>一张 Map 里放齐三组候选，尺寸刻意各不相同，便于验证字节记账：
 *
 * <pre>
 * RF-1  README.md                                    DOCUMENTATION      FOUNDATION   100
 * RF-2  db/schema.sql                                DATA_SCHEMA        FOUNDATION   100
 * RF-3  docs/api.md                                  DOCUMENTATION      FOUNDATION   100
 * RF-4  docs/guide.md                                DOCUMENTATION      FOUNDATION   100
 * RF-5  pom.xml                                      BUILD_METADATA     FOUNDATION   100
 * RF-6  scripts/build.sh                             SCRIPT_AUTOMATION  FOUNDATION   100
 * RF-7  src/main/java/com/hmdp/config/MvcConfig.java SOURCE_CODE        FOUNDATION   100
 * RF-8  …/controller/BlogController.java             SOURCE_CODE        SCOUT_SOURCE 100
 * RF-9  …/controller/ShopController.java             SOURCE_CODE        SCOUT_SOURCE 200
 * RF-10 …/entity/Shop.java                           SOURCE_CODE        SCOUT_SOURCE  50
 * RF-11 …/mapper/ShopMapper.java                     SOURCE_CODE        SCOUT_SOURCE 100
 * RF-12 …/service/impl/ShopServiceImpl.java          SOURCE_CODE        SCOUT_SOURCE 100
 * RF-13 …/service/impl/VoucherOrderServiceImpl.java  SOURCE_CODE        SCOUT_SOURCE 100
 * RF-14 src/main/resources/application.yaml          CONFIGURATION      FOUNDATION   100
 * RF-15 src/test/java/com/hmdp/ShopTest.java         TEST_CODE          NONE         100
 * </pre>
 *
 * <p>Foundation 的类别轮转顺序由 {@code RepositoryMaterialKind} 的声明顺序决定，因此固定为：
 *
 * <pre>
 * SOURCE_CODE(RF-7) → BUILD_METADATA(RF-5) → CONFIGURATION(RF-14) → DATA_SCHEMA(RF-2)
 * → DOCUMENTATION(RF-1, RF-3, RF-4) → SCRIPT_AUTOMATION(RF-6)
 * </pre>
 */
final class ReadPlanFixtures {

    static final String REVISION = "18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4";

    static final int README = 1;
    static final int SCHEMA = 2;
    static final int DOCS_API = 3;
    static final int DOCS_GUIDE = 4;
    static final int POM = 5;
    static final int SCRIPT = 6;
    static final int MVC_CONFIG = 7;
    static final int BLOG_CONTROLLER = 8;
    static final int SHOP_CONTROLLER = 9;
    static final int SHOP_ENTITY = 10;
    static final int SHOP_MAPPER = 11;
    static final int SHOP_SERVICE_IMPL = 12;
    static final int VOUCHER_ORDER_SERVICE_IMPL = 13;
    static final int APPLICATION_YAML = 14;
    static final int SHOP_TEST = 15;

    private ReadPlanFixtures() {
    }

    static RepositoryMap map() {
        return map(REVISION);
    }

    static RepositoryMap map(String revision) {
        List<String> paths = List.of(
                "README.md",
                "db/schema.sql",
                "docs/api.md",
                "docs/guide.md",
                "pom.xml",
                "scripts/build.sh",
                "src/main/java/com/hmdp/config/MvcConfig.java",
                "src/main/java/com/hmdp/controller/BlogController.java",
                "src/main/java/com/hmdp/controller/ShopController.java",
                "src/main/java/com/hmdp/entity/Shop.java",
                "src/main/java/com/hmdp/mapper/ShopMapper.java",
                "src/main/java/com/hmdp/service/impl/ShopServiceImpl.java",
                "src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java",
                "src/main/resources/application.yaml",
                "src/test/java/com/hmdp/ShopTest.java");

        List<RepositoryMapEntry> entries = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            RepositoryPathClassifier.Classification classification =
                    RepositoryPathClassifier.classify(path);
            entries.add(new RepositoryMapEntry(
                    RepositoryFileReference.of(index + 1),
                    path,
                    sizeOf(index + 1),
                    classification.language(),
                    classification.materialKind(),
                    classification.roleHints()));
        }
        return RepositoryMap.of(revision, entries);
    }

    /** 与上面那张表一致的尺寸；只在这里定义一次，测试按编号引用。 */
    static long sizeOf(int position) {
        return switch (position) {
            case SHOP_CONTROLLER -> 200;
            case SHOP_ENTITY -> 50;
            default -> 100;
        };
    }

    /**
     * 用指定的若干文件另建一张 Map，引用按新位置重新编号为 {@code RF-1…RF-n}。
     *
     * <p>路径与尺寸沿用上面那张表里的取值，因此测试可以继续按语义引用它们。
     * 用途是构造「只有源码、没有基础材料」这类子集场景。
     */
    static RepositoryMap subsetMap(String revision, int... sourcePositions) {
        RepositoryMap canonical = map();
        List<RepositoryMapEntry> entries = new ArrayList<>(sourcePositions.length);
        int position = 1;
        for (int sourcePosition : sourcePositions) {
            RepositoryMapEntry source = entry(canonical, sourcePosition);
            RepositoryPathClassifier.Classification classification =
                    RepositoryPathClassifier.classify(source.relativePath());
            entries.add(new RepositoryMapEntry(
                    RepositoryFileReference.of(position++),
                    source.relativePath(),
                    source.sizeInBytes(),
                    classification.language(),
                    classification.materialKind(),
                    classification.roleHints()));
        }
        return RepositoryMap.of(revision, entries);
    }

    /**
     * 一张**声明同一个 revision、编号位数也一样、但两个源码编号指向别的文件**的 Map。
     *
     * <p>它的 {@code RF-1…RF-7} 与上面那张表完全一致，只有 {@code RF-8} 与 {@code RF-9}
     * 互换了：这里 {@code RF-8} 是 {@code ShopController.java}（上面是 {@code BlogController.java}），
     * {@code RF-9} 是 {@code BlogController.java}（上面是 {@code ShopController.java}）。
     *
     * <p>两个文件都是源码候选，位数也没有越界——因此只有真正逐条核对描述符，
     * 才能发现这份计划不是由本次这张 Map 产生的。
     */
    static RepositoryMap foreignMapWithSameRevision() {
        return subsetMap(REVISION,
                README, SCHEMA, DOCS_API, DOCS_GUIDE, POM, SCRIPT, MVC_CONFIG,
                SHOP_CONTROLLER, BLOG_CONTROLLER, SHOP_ENTITY, SHOP_MAPPER,
                SHOP_SERVICE_IMPL, VOUCHER_ORDER_SERVICE_IMPL);
    }

    static RepositoryMapEntry entry(RepositoryMap map, int position) {
        return map.find(RepositoryFileReference.of(position)).orElseThrow(
                () -> new AssertionError("夹具里没有 RF-" + position));
    }

    static RepositoryInspectionArea area(RepositoryMap map, String label, int... positions) {
        List<RepositoryMapEntry> entries = new ArrayList<>(positions.length);
        for (int position : positions) {
            entries.add(entry(map, position));
        }
        return new RepositoryInspectionArea(label, entries);
    }

    static RepositoryInspectionPlan plan(RepositoryMap map, RepositoryInspectionArea... areas) {
        return RepositoryInspectionPlan.of(map.analyzedRevision(), List.of(areas));
    }

    /** 覆盖全部源码候选的三区域计划，用于「预算互不影响」这类整体检查。 */
    static RepositoryInspectionPlan fullPlan(RepositoryMap map) {
        return plan(map,
                area(map, "接口", BLOG_CONTROLLER, SHOP_CONTROLLER),
                area(map, "领域与持久化", SHOP_ENTITY, SHOP_MAPPER),
                area(map, "服务实现", SHOP_SERVICE_IMPL, VOUCHER_ORDER_SERVICE_IMPL));
    }
}
