package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryPathClassifier;
import java.util.List;

/**
 * Region 测试共用的 Map 与路径。
 *
 * <p>一张 Map 里同时放齐三组候选，这样每个测试都能直接断言「哪一组没有参与 Region 导航」：
 *
 * <pre>
 * RF-1  pom.xml                                        FOUNDATION
 * RF-2  …/config/AppConfig.java                        FOUNDATION（CONFIG_BOOTSTRAP）
 * RF-3  …/controller/OrderController.java              SCOUT_SOURCE
 * RF-4  …/service/OrderService.java                    SCOUT_SOURCE
 * RF-5  …/entity/Order.java                            SCOUT_SOURCE
 * RF-6  …/mapper/OrderMapper.java                      SCOUT_SOURCE
 * RF-7  src/test/java/com/app/OrderTest.java           NONE（TEST_CODE）
 * RF-8  web/src/api/client.ts                          SCOUT_SOURCE
 * RF-9  web/src/ui/App.tsx                             SCOUT_SOURCE
 * RF-10 svc/handler/order.go                           SCOUT_SOURCE
 * RF-11 main.py                                        SCOUT_SOURCE（仓库根目录，无目录前缀）
 * RF-12 svc/handler/user.go                            SCOUT_SOURCE
 * </pre>
 *
 * <p>因此源码候选是 RF-3…RF-6、RF-8…RF-12 共 9 个，根层 Region 是 {@code src} / {@code svc} /
 * {@code web} 三个；{@code svc/handler} 是唯一一个「直接含两个源码文件」的目录。
 */
final class RegionFixtures {

    static final String REVISION = "region-rev-1";

    static final String POM = "pom.xml";
    static final String APP_CONFIG = "src/main/java/com/app/config/AppConfig.java";
    static final String CONTROLLER = "src/main/java/com/app/controller/OrderController.java";
    static final String SERVICE = "src/main/java/com/app/service/OrderService.java";
    static final String ENTITY = "src/main/java/com/app/entity/Order.java";
    static final String MAPPER = "src/main/java/com/app/mapper/OrderMapper.java";
    static final String TEST = "src/test/java/com/app/OrderTest.java";
    static final String WEB_CLIENT = "web/src/api/client.ts";
    static final String WEB_UI = "web/src/ui/App.tsx";
    static final String SVC_ORDER = "svc/handler/order.go";
    static final String SVC_USER = "svc/handler/user.go";
    static final String ROOT_MAIN = "main.py";

    private RegionFixtures() {
    }

    static RepositoryMap map() {
        return RepositoryMap.of(REVISION, List.of(
                entry(1, POM, 4_096),
                entry(2, APP_CONFIG, 1_500),
                entry(3, CONTROLLER, 3_000),
                entry(4, SERVICE, 12_000),
                entry(5, ENTITY, 700),
                entry(6, MAPPER, 400),
                entry(7, TEST, 2_200),
                entry(8, WEB_CLIENT, 900),
                entry(9, WEB_UI, 1_100),
                entry(10, SVC_ORDER, 800),
                entry(11, ROOT_MAIN, 300),
                entry(12, SVC_USER, 820)));
    }

    /** 与 {@link #map()} 完全相同，只有 sizeInBytes 不同：用于证明 Region 只依赖元数据。 */
    static RepositoryMap mapWithDifferentSizes() {
        return RepositoryMap.of(REVISION, List.of(
                entry(1, POM, 99),
                entry(2, APP_CONFIG, 98),
                entry(3, CONTROLLER, 97),
                entry(4, SERVICE, 96),
                entry(5, ENTITY, 95),
                entry(6, MAPPER, 94),
                entry(7, TEST, 93),
                entry(8, WEB_CLIENT, 92),
                entry(9, WEB_UI, 91),
                entry(10, SVC_ORDER, 90),
                entry(11, ROOT_MAIN, 89),
                entry(12, SVC_USER, 88)));
    }

    static RepositoryRegionTree tree() {
        return RepositoryRegionTree.of(map());
    }

    /** 根层 Region Catalog：三个 Region（src / svc / web）。 */
    static RepositoryRegionCatalog catalog() {
        return RepositoryRegionCatalog.of(REVISION, tree().rootRegions());
    }

    /**
     * 一个结构合法的响应：按优先级选了给定位置的区域（1 起）。
     *
     * <p>引用必须从目录本身取——它带着本次调用的作用域，不能凭空写一个 {@code RR-1}。
     */
    static String selectionResponse(RepositoryRegionCatalog catalog, int... positions) {
        StringBuilder json = new StringBuilder("{\"regionRefs\":[");
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"')
                    .append(catalog.entries().get(positions[i] - 1).reference().value())
                    .append('"');
        }
        return json.append("]}").toString();
    }

    /** 一个结构合法的响应：按优先级选了 src 与 svc。 */
    static String validResponse() {
        return selectionResponse(catalog(), 1, 2);
    }

    static RepositoryMapEntry entry(int position, String relativePath, long sizeInBytes) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(relativePath);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(position),
                relativePath,
                sizeInBytes,
                classification.language(),
                classification.materialKind(),
                classification.roleHints());
    }

    static RepositoryCandidateLane laneOf(String relativePath) {
        return RepositoryCandidateLane.of(entry(1, relativePath, 1));
    }
}
