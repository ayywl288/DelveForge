package com.ayywl.delveforge.application.repositoryanalysis.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Repository Map 自身的不变量。
 *
 * <p>这些条件决定「引用能不能被解析」这件事是否可靠，因此它们在 Map 构造时就成立，
 * 而不是留给消费方在使用时各自检查。
 */
class RepositoryMapTest {

    private static final String REVISION = "abc123def456";

    @Test
    void keepsEntriesInTheGivenOrder() {
        RepositoryMap map = RepositoryMap.of(REVISION, List.of(
                entry(1, "a.java"), entry(2, "b.java"), entry(3, "c.java")));

        assertEquals(List.of("a.java", "b.java", "c.java"),
                map.entries().stream().map(RepositoryMapEntry::relativePath).toList());
        assertEquals(3, map.size());
    }

    /**
     * 引用必须按位置编号。
     *
     * <p>{@code RF-*} 的意义是「本次 Map 中第几个描述符」。如果编号可以任意给定，
     * 「引用能否解析」就会退化成一次不可预期的查找；强制按位置编号之后，第 i 个条目
     * 的引用一定是 {@code RF-i}，这件事就不再需要约定。
     */
    @Test
    void rejectsReferencesThatDoNotMatchTheirPosition() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RepositoryMap.of(REVISION, List.of(entry(2, "a.java"))));

        assertTrue(failure.getMessage().contains("RF-1"),
                "错误信息应当说明期望的引用: " + failure.getMessage());
    }

    @Test
    void rejectsDuplicatePaths() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryMap.of(REVISION, List.of(entry(1, "a.java"), entry(2, "a.java"))));
    }

    @Test
    void rejectsMissingRevision() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryMap.of(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> RepositoryMap.of("  ", List.of()));
    }

    @Test
    void rejectsNullEntries() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryMap.of(REVISION, null));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryMap.of(REVISION, Arrays.asList(entry(1, "a.java"), null)));
    }

    @Test
    void allowsAnEmptyMap() {
        RepositoryMap map = RepositoryMap.of(REVISION, List.of());

        assertTrue(map.isEmpty());
        assertEquals(REVISION, map.analyzedRevision());
        assertTrue(map.find(RepositoryFileReference.of(1)).isEmpty());
    }

    @Test
    void entriesStayImmutable() {
        RepositoryMap map = RepositoryMap.of(REVISION, List.of(entry(1, "a.java")));

        assertThrows(UnsupportedOperationException.class,
                () -> map.entries().add(entry(2, "b.java")));
    }

    @Test
    void rejectsNullLaneWhenFiltering() {
        RepositoryMap map = RepositoryMap.of(REVISION, List.of(entry(1, "a.java")));

        assertThrows(IllegalArgumentException.class, () -> map.entriesIn(null));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private static RepositoryMapEntry entry(int position, String relativePath) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(relativePath);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(position),
                relativePath,
                512,
                classification.language(),
                classification.materialKind(),
                classification.roleHints());
    }
}
