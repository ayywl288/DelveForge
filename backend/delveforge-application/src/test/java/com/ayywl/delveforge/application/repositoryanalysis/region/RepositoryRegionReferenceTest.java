package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 引用取值的形状：完整 UUID 作用域 + 位置，以及格式判定。 */
class RepositoryRegionReferenceTest {

    private static final String SCOPE = RegionFixtures.SCOPE_A;

    @Test
    void buildsScopedReference() {
        assertEquals("RR-" + SCOPE + "-7",
                RepositoryRegionReference.of(SCOPE, 7).value());
    }

    @Test
    void scopeMustBeTheFullUuidNotATruncation() {
        assertEquals(32, RepositoryRegionReference.SCOPE_HEX_LENGTH);

        for (String badScope : new String[] {
                null, "",
                "ABC",                                        // 不是十六进制
                SCOPE.toUpperCase(),                          // 必须小写
                "3f1a9c02",                                   // 截短到 8 位 —— 碰撞空间只有 2^32
                SCOPE.substring(0, SCOPE.length() - 1),       // 少一位
                SCOPE + "0"}) {                               // 多一位
            assertThrows(IllegalArgumentException.class,
                    () -> RepositoryRegionReference.of(badScope, 1),
                    "非法作用域必须拒绝: " + badScope);
        }
    }

    @Test
    void rejectsInvalidPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionReference.of(SCOPE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionReference.of(SCOPE, -1));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionReference(null));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionReference("  "));
    }

    @Test
    void recognisesWellFormedValues() {
        assertTrue(RepositoryRegionReference.isWellFormed("RR-" + SCOPE + "-1"));
        assertTrue(RepositoryRegionReference.isWellFormed(
                "RR-00000000000000000000000000000000-42"));

        for (String bad : new String[] {
                null, "", "RR-1",
                "RR-" + SCOPE + "-0",
                "RR-" + SCOPE + "-01",
                "RR-" + SCOPE.toUpperCase() + "-1",
                "RF-" + SCOPE + "-1",
                "RR-" + SCOPE,
                "RR-3f1a9c02-1"}) {
            assertFalse(RepositoryRegionReference.isWellFormed(bad),
                    "不应接受: " + bad);
        }
    }
}
