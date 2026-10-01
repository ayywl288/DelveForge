package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证预算的取值范围。
 *
 * <p>只拒绝明显不成立的组合：数值本身怎么定是使用方的决定，本 Task 不提供默认值。
 */
class RepositoryMaterialBudgetTest {

    @Test
    void acceptsUsableBudgets() {
        RepositoryMaterialBudget budget = new RepositoryMaterialBudget(40, 20_000, 200_000);

        assertEquals(40, budget.maxFiles());
        assertEquals(20_000, budget.maxFileBytes());
        assertEquals(200_000, budget.maxTotalBytes());
    }

    /**
     * 总量刚好等于单文件上限是合法的：那表示「最多读一个满尺寸的文件」。
     */
    @Test
    void acceptsTotalEqualToSingleFileLimit() {
        assertEquals(1_000,
                new RepositoryMaterialBudget(5, 1_000, 1_000).maxTotalBytes());
    }

    @Test
    void rejectsNonPositiveMaxFiles() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryMaterialBudget(0, 100, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryMaterialBudget(-1, 100, 1_000));
    }

    @Test
    void rejectsNonPositiveMaxFileBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryMaterialBudget(5, 0, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryMaterialBudget(5, -100, 1_000));
    }

    /**
     * 总量小于单文件上限意味着「任何一个达到上限的文件都放不下」——那几乎一定是写错了。
     */
    @Test
    void rejectsTotalSmallerThanSingleFileLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryMaterialBudget(5, 1_000, 999));
    }
}
