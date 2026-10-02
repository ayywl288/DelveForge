package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryRoleHint;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Region 视图的确定性构造：只由 SCOUT_SOURCE 决定，只依赖元数据。 */
class RepositoryRegionTreeTest {

    @Test
    void buildsRootRegionsFromScoutSourceOnly() {
        RepositoryRegionTree tree = RegionFixtures.tree();

        assertEquals(List.of("src", "svc", "web"),
                tree.rootRegions().stream().map(RepositoryRegion::pathPrefix).toList(),
                "根层 Region 只由 SCOUT_SOURCE 决定，并按路径升序");

        RepositoryRegion src = tree.rootRegions().get(0);
        assertEquals(0, src.directSourceFileCount());
        assertEquals(4, src.descendantSourceFileCount(),
                "src 下有 controller / service / entity / mapper 四个源码候选");
        assertEquals(1, src.childRegionCount(), "src 只有一个含源码的直接子目录");
        assertEquals(List.of("JAVA"),
                src.languages().stream().map(Enum::name).toList());
    }

    @Test
    void foundationAndNoneCandidatesFormNoRegion() {
        RepositoryRegionTree tree = RegionFixtures.tree();

        assertTrue(tree.region("src/main/java/com/app/config").isEmpty(),
                "配置类属于 FOUNDATION，不参与 Region 导航");
        assertTrue(tree.region("src/test/java/com/app").isEmpty(),
                "测试代码属于 NONE，不参与 Region 导航");
        assertTrue(tree.region("src/test").isEmpty());
        // pom.xml 是仓库根目录下的 FOUNDATION 文件，同样不形成 Region
        assertEquals(0, tree.rootRegions().stream()
                .filter(region -> region.pathPrefix().equals("pom.xml"))
                .count());
    }

    @Test
    void countsDirectAndDescendantSourceFiles() {
        RepositoryRegionTree tree = RegionFixtures.tree();

        RepositoryRegion handler = tree.region("svc/handler").orElseThrow();
        assertEquals(2, handler.directSourceFileCount());
        assertEquals(2, handler.descendantSourceFileCount(),
                "直接位于该目录的文件同时计入后代数");
        assertEquals(0, handler.childRegionCount());

        RepositoryRegion app = tree.region("src/main/java/com/app").orElseThrow();
        assertEquals(0, app.directSourceFileCount());
        assertEquals(4, app.descendantSourceFileCount());
        assertEquals(4, app.childRegionCount(),
                "controller / entity / mapper / service 四个含源码的子目录");

        RepositoryRegion src = tree.region("src").orElseThrow();
        assertEquals(4, src.descendantSourceFileCount(),
                "深单链上的每一层都保留同一批后代");
    }

    @Test
    void summarizesLanguagesAndRoleHintsPerRegion() {
        RepositoryRegionTree tree = RegionFixtures.tree();

        RepositoryRegion api = tree.region("web/src/api").orElseThrow();
        assertEquals(List.of("TYPESCRIPT"),
                api.languages().stream().map(Enum::name).toList());
        assertTrue(api.roleHints().contains(RepositoryRoleHint.API_ENTRY));

        RepositoryRegion web = tree.region("web").orElseThrow();
        assertEquals(List.of("TYPESCRIPT"),
                web.languages().stream().map(Enum::name).toList());

        RepositoryRegion svc = tree.region("svc").orElseThrow();
        assertEquals(List.of("GO"), svc.languages().stream().map(Enum::name).toList());
    }

    @Test
    void ordersChildRegionsByPathAscending() {
        RepositoryRegionTree tree = RegionFixtures.tree();

        assertEquals(
                List.of("src/main/java/com/app/controller",
                        "src/main/java/com/app/entity",
                        "src/main/java/com/app/mapper",
                        "src/main/java/com/app/service"),
                tree.childRegions("src/main/java/com/app").stream()
                        .map(RepositoryRegion::pathPrefix).toList());
    }

    @Test
    void rootLevelSourceFilesBelongToNoRegionButAreStillCounted() {
        RepositoryRegionTree tree = RegionFixtures.tree();

        assertEquals(1, tree.rootDirectSourceFileCount(),
                "main.py 在仓库根目录，不属于任何目录前缀，但必须被显式计数而不是静默丢弃");
        assertTrue(tree.region("main.py").isEmpty());
        assertFalse(tree.rootRegions().stream()
                .anyMatch(region -> region.pathPrefix().contains("main.py")));
    }

    @Test
    void isDeterministicAndDependsOnlyOnMetadataNotOnContentSize() {
        RepositoryRegionTree first = RegionFixtures.tree();
        RepositoryRegionTree second = RepositoryRegionTree.of(RegionFixtures.map());
        assertEquals(first.rootRegions(), second.rootRegions(),
                "同一份输入构造两次必须得到同一棵树");

        // 同一张 Map 的路径与语言不变、只有 sizeInBytes 不同：Region 视图必须逐项相同。
        RepositoryRegionTree resized =
                RepositoryRegionTree.of(RegionFixtures.mapWithDifferentSizes());
        assertEquals(first.rootRegions(), resized.rootRegions(),
                "Region 只依赖路径与分类元数据，不依赖文件大小或内容");
        assertEquals(first.region("src/main/java/com/app").orElseThrow(),
                resized.region("src/main/java/com/app").orElseThrow());
    }

    @Test
    void doesNotMutateTheMap() {
        RepositoryMap map = RegionFixtures.map();
        List<RepositoryMapEntry> before = map.entries();

        RepositoryRegionTree.of(map);

        assertEquals(before, map.entries(), "建立 Region 视图不得修改 Repository Map");
        assertEquals(12, map.size());
    }

    @Test
    void emptyTreeWhenThereAreNoSourceCandidates() {
        RepositoryMap map = RepositoryMap.of(RegionFixtures.REVISION, List.of(
                RegionFixtures.entry(1, RegionFixtures.POM, 10),
                RegionFixtures.entry(2, RegionFixtures.TEST, 10)));

        RepositoryRegionTree tree = RepositoryRegionTree.of(map);

        assertTrue(tree.rootRegions().isEmpty());
        assertEquals(0, tree.size());
        assertEquals(0, tree.rootDirectSourceFileCount());
        assertEquals(RegionFixtures.REVISION, tree.analyzedRevision());
    }

    @Test
    void rejectsNullMapAndNullPrefix() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryRegionTree.of(null));

        RepositoryRegionTree tree = RegionFixtures.tree();
        assertThrows(IllegalArgumentException.class, () -> tree.childRegions(null));
        assertTrue(tree.childRegions("does/not/exist").isEmpty());
        assertTrue(tree.region("does/not/exist").isEmpty());
        assertTrue(tree.region(null).isEmpty());
    }

    @Test
    void scoutSourceLaneIsWhatTheFixtureAssumes() {
        // 这条断言把 fixture 的假设钉住：分类规则一旦变化，失败会指向 fixture 而不是 Region 逻辑。
        assertEquals(RepositoryCandidateLane.SCOUT_SOURCE,
                RegionFixtures.laneOf(RegionFixtures.SVC_ORDER));
        assertEquals(RepositoryCandidateLane.SCOUT_SOURCE,
                RegionFixtures.laneOf(RegionFixtures.ROOT_MAIN));
        assertEquals(RepositoryCandidateLane.FOUNDATION,
                RegionFixtures.laneOf(RegionFixtures.APP_CONFIG));
        assertEquals(RepositoryCandidateLane.NONE,
                RegionFixtures.laneOf(RegionFixtures.TEST));
    }
}
