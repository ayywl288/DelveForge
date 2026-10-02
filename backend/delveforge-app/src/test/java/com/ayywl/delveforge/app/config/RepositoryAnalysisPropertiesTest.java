package com.ayywl.delveforge.app.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.repositoryanalysis.region.RegionNavigationLimits;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionRecursionBudget;
import org.junit.jupiter.api.Test;

/**
 * {@code delveforge.repository-analysis.region} 的取值约束。
 *
 * <p>这里断言的是**配置层与 Application 层接受同一组取值**：两层各写一份校验，
 * 一旦一层比另一层严，就会出现「Application 接受、配置拒绝 → 应用起不来」这种
 * 只在配置里才暴露的问题。
 */
class RepositoryAnalysisPropertiesTest {

    private static RepositoryAnalysisProperties.Region region(int catalogBytes,
                                                              int selected,
                                                              int rounds,
                                                              int calls) {
        return new RepositoryAnalysisProperties.Region(catalogBytes, selected, rounds, calls);
    }

    @Test
    void acceptsBudgetsThatDifferInMagnitude() {
        // 总调用数小于单分支轮数上限：两者独立，这是合法组合（宽度先耗尽而已）。
        assertDoesNotThrow(() -> region(65_536, 6, 8, 2));
        assertDoesNotThrow(() -> region(65_536, 6, 2, 8));
        assertDoesNotThrow(() -> region(65_536, 6, 1, 1));
        assertDoesNotThrow(() -> region(65_536, 6, 8, 8));
    }

    @Test
    void configurationAndApplicationAgreeOnTheSameValues() {
        RepositoryAnalysisProperties.Region accepted = region(65_536, 6, 8, 2);

        RegionNavigationLimits callLimits = accepted.toLimits();
        RegionRecursionBudget recursion = accepted.toRecursionBudget();

        assertEquals(65_536, callLimits.maxCatalogBytes());
        assertEquals(6, callLimits.maxSelectedRegions());
        assertEquals(8, recursion.maxRoundsPerBranch());
        assertEquals(2, recursion.maxRegionScoutCalls());
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> region(0, 6, 8, 12));
        assertThrows(IllegalArgumentException.class, () -> region(65_536, 0, 8, 12));
        assertThrows(IllegalArgumentException.class, () -> region(65_536, 6, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> region(65_536, 6, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> region(65_536, 6, -1, 12));
        assertThrows(IllegalArgumentException.class, () -> region(65_536, 6, 8, -1));
    }
}
