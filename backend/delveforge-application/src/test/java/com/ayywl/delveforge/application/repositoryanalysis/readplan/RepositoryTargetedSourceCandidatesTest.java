package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMaterialKind;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryPathClassifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 分层 Scout 候选流自身的约束：编号与路径都不得重复，revision 必须给出。
 *
 * <p>同一条路径用两个不同编号出现，物理上仍是同一个文件；允许它会让它被读两次，
 * 也会让「读了几个文件」的记账与真实不符（与 {@code RepositoryReadPlan} 同一条口径）。
 */
class RepositoryTargetedSourceCandidatesTest {

    private static final String REVISION = "rev-candidates-1";

    @Test
    void keepsTheGivenOrder() {
        RepositoryMapEntry first = entry(1, "pkg/a/A1.java");
        RepositoryMapEntry second = entry(2, "pkg/a/A2.java");

        RepositoryTargetedSourceCandidates candidates =
                RepositoryTargetedSourceCandidates.of(REVISION, List.of(second, first));

        assertEquals(List.of(second, first), candidates.orderedCandidates(),
                "顺序就是考虑顺序，不得被重排");
        assertEquals(REVISION, candidates.analyzedRevision());
        assertEquals(2, candidates.size());
    }

    @Test
    void rejectsDuplicateReferences() {
        RepositoryMapEntry entry = entry(1, "pkg/a/A1.java");

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryTargetedSourceCandidates.of(REVISION, List.of(entry, entry)));
    }

    /** 不同编号、同一个路径：仍然是同一个文件，必须拒绝。 */
    @Test
    void rejectsTheSamePathUnderTwoReferences() {
        RepositoryMapEntry first = entry(1, "pkg/a/A1.java");
        RepositoryMapEntry second = entry(2, "pkg/a/A1.java");

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryTargetedSourceCandidates.of(REVISION, List.of(first, second)));
    }

    @Test
    void rejectsMissingRevisionEmptyListAndNullElements() {
        RepositoryMapEntry entry = entry(1, "pkg/a/A1.java");
        List<RepositoryMapEntry> withNull = new ArrayList<>();
        withNull.add(entry);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryTargetedSourceCandidates.of(null, List.of(entry)));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryTargetedSourceCandidates.of("  ", List.of(entry)));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryTargetedSourceCandidates.of(REVISION, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryTargetedSourceCandidates.of(REVISION, null));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryTargetedSourceCandidates.of(REVISION, withNull));
    }

    private static RepositoryMapEntry entry(int position, String relativePath) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(relativePath);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(position),
                relativePath,
                100,
                classification.language(),
                RepositoryMaterialKind.SOURCE_CODE,
                classification.roleHints());
    }
}
