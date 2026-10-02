package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryLanguage;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryRoleHint;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Region 描述符自身的约束：路径安全、计数自洽、摘要去重排序。 */
class RepositoryRegionTest {

    private static RepositoryRegion region(String prefix, int direct, int descendant,
                                           int children,
                                           List<RepositoryLanguage> languages,
                                           List<RepositoryRoleHint> roleHints) {
        return new RepositoryRegion(prefix, direct, descendant, children, languages, roleHints);
    }

    @Test
    void rejectsUnsafePathPrefixes() {
        for (String bad : List.of("", "  ", "/abs/path", "trailing/", "back\\slash",
                "C:/drive/form", "double//segment")) {
            assertThrows(IllegalArgumentException.class,
                    () -> region(bad, 0, 1, 0, List.of(), List.of()),
                    "不合法的目录前缀必须拒绝: " + bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> region(null, 0, 1, 0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> region("C:\\drive", 0, 1, 0, List.of(), List.of()));
    }

    @Test
    void rejectsInconsistentCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> region("src", 5, 2, 0, List.of(), List.of()),
                "后代数不能小于直接文件数");
        assertThrows(IllegalArgumentException.class,
                () -> region("src", -1, 1, 0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> region("src", 0, 1, -1, List.of(), List.of()));
    }

    @Test
    void normalizesLanguageAndRoleHintSummaries() {
        RepositoryRegion region = region("src", 1, 3, 0,
                Arrays.asList(RepositoryLanguage.JAVA, RepositoryLanguage.JAVA,
                        RepositoryLanguage.PYTHON),
                Arrays.asList(RepositoryRoleHint.UTILITY, RepositoryRoleHint.API_ENTRY));

        assertEquals(List.of(RepositoryLanguage.JAVA, RepositoryLanguage.PYTHON),
                region.languages(), "语言摘要去重并按枚举序排列");
        assertEquals(List.of(RepositoryRoleHint.API_ENTRY, RepositoryRoleHint.UTILITY),
                region.roleHints(), "角色摘要去重并按枚举序排列");
    }

    @Test
    void rejectsNullSummaries() {
        assertThrows(IllegalArgumentException.class,
                () -> region("src", 0, 1, 0, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> region("src", 0, 1, 0, List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> region("src", 0, 1, 0,
                        Arrays.asList(RepositoryLanguage.JAVA, null), List.of()));
    }

    @Test
    void acceptsADirectoryWithOnlyDirectFiles() {
        RepositoryRegion region = region("svc/handler", 2, 2, 0, List.of(), List.of());

        assertEquals(2, region.directSourceFileCount());
        assertEquals(2, region.descendantSourceFileCount());
        assertEquals(0, region.childRegionCount());
    }
}
