package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 引用取值的形状：作用域 + 位置，以及格式判定。 */
class RepositoryRegionReferenceTest {

    @Test
    void buildsScopedReference() {
        assertEquals("RR-3f1a9c02-7",
                RepositoryRegionReference.of("3f1a9c02", 7).value());
    }

    @Test
    void rejectsInvalidScopeOrPosition() {
        for (String badScope : new String[] {null, "", "ABC", "3F1A9C02", "3f1a9c0", "3f1a9c022"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RepositoryRegionReference.of(badScope, 1),
                    "非法作用域必须拒绝: " + badScope);
        }
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionReference.of("3f1a9c02", 0));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionReference.of("3f1a9c02", -1));
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
        assertTrue(RepositoryRegionReference.isWellFormed("RR-3f1a9c02-1"));
        assertTrue(RepositoryRegionReference.isWellFormed("RR-00000000-42"));

        for (String bad : new String[] {null, "", "RR-1", "RR-3f1a9c02-0",
                "RR-3f1a9c02-01", "RR-3F1A9C02-1", "RF-3f1a9c02-1", "RR-3f1a9c02"}) {
            assertFalse(RepositoryRegionReference.isWellFormed(bad),
                    "不应接受: " + bad);
        }
    }
}
