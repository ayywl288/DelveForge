package com.ayywl.delveforge.application.repositoryanalysis.scout;

import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.APPLICATION;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.CONFIG;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.CONTROLLER_REF;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.ENTITY;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.MAPPER;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.POM;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.SERVICE_IMPL;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryLanguage;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMaterialKind;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryRoleHint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Scout 输入的构造：只取源码候选，顺序确定，元数据完整。
 */
class RepositoryScoutInputsTest {

    @Test
    void includesOnlyScoutSourceEntries() {
        RepositoryScoutInputs inputs = ScoutFixtures.inputs();

        assertEquals(
                List.of(CONTROLLER, ENTITY, MAPPER, SERVICE_IMPL),
                pathsOf(inputs));
    }

    /**
     * Foundation 与 NONE 都不进入清单。
     *
     * <p>它们仍然在那张 Map 里（Map 表示完整的树），只是这一阶段不把它们交给 Scout：
     * 基础材料走 Foundation 那条路，测试与生成物当前不参与选取。
     */
    @Test
    void excludesFoundationAndNoneEntries() {
        RepositoryScoutInputs inputs = ScoutFixtures.inputs();
        List<String> catalog = pathsOf(inputs);

        for (String excluded : List.of(POM, APPLICATION, CONFIG, TEST)) {
            assertTrue(!catalog.contains(excluded), "不该进入 Scout 清单: " + excluded);
        }
        assertEquals(4, inputs.size());
    }

    @Test
    void preservesTheAnalyzedRevision() {
        assertEquals(ScoutFixtures.REVISION, ScoutFixtures.inputs().analyzedRevision());
    }

    /**
     * 描述符的元数据完整地进入清单——Scout 只能依据这些字段判断，
     * 少给一项就等于替它丢掉一个信号。
     */
    @Test
    void keepsEveryDescriptorField() {
        RepositoryMapEntry controller = ScoutFixtures.inputs().catalog().get(0);

        assertEquals("RF-4", controller.reference().value());
        assertEquals(CONTROLLER, controller.relativePath());
        assertEquals(3_000, controller.sizeInBytes());
        assertEquals(RepositoryLanguage.JAVA, controller.language());
        assertEquals(RepositoryMaterialKind.SOURCE_CODE, controller.materialKind());
        assertEquals(List.of(RepositoryRoleHint.API_ENTRY), controller.roleHints());
    }

    @Test
    void catalogOrderIsDeterministic() {
        assertEquals(ScoutFixtures.inputs().catalog(), ScoutFixtures.inputs().catalog());
    }

    /**
     * 清单里的描述符就是 Map 里那些对象本身，不是副本。
     *
     * <p>这一点是引用校验能成立的前提：解析回来的必须与清单里发出的是同一个描述符，
     * 否则「模型指的是不是我们给过的东西」就退化成一次按值比较。
     */
    @Test
    void catalogEntriesAreTheVeryMapEntries() {
        RepositoryScoutInputs inputs = ScoutFixtures.inputs();
        RepositoryMapEntry fromCatalog = inputs.catalog().get(0);

        assertSame(fromCatalog, inputs.findInMap(ScoutFixtures.ref(CONTROLLER_REF)).orElseThrow());
    }

    /**
     * {@link RepositoryScoutInputs#findInMap} 覆盖整张 Map，不只源码候选。
     *
     * <p>引用校验需要据此把「编号不存在」与「编号存在但不是源码候选」区分开。
     */
    @Test
    void findInMapSeesEntriesOutsideTheScoutCatalog() {
        RepositoryScoutInputs inputs = ScoutFixtures.inputs();

        assertTrue(inputs.findInMap(ScoutFixtures.ref(ScoutFixtures.POM_REF)).isPresent(),
                "Foundation 的编号在 Map 里是存在的，只是不进 Scout 清单");
        assertTrue(inputs.findInMap(ScoutFixtures.ref(ScoutFixtures.TEST_REF)).isPresent());
        assertTrue(inputs.findInMap(ScoutFixtures.ref(99)).isEmpty());
    }

    @Test
    void rejectsNullMap() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryScoutInputs.of(null));
    }

    /**
     * 没有任何源码候选时直接拒绝，不产生一次注定失败的调用。
     */
    @Test
    void rejectsMapWithoutAnyScoutSourceEntry() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RepositoryScoutInputs.of(ScoutFixtures.mapWithoutScoutSource()));

        assertTrue(failure.getMessage().contains(ScoutFixtures.REVISION),
                "错误信息应当带上 revision: " + failure.getMessage());
    }

    private static List<String> pathsOf(RepositoryScoutInputs inputs) {
        return inputs.catalog().stream().map(RepositoryMapEntry::relativePath).toList();
    }
}
