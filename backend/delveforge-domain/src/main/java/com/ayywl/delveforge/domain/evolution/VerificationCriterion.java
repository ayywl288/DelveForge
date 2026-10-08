package com.ayywl.delveforge.domain.evolution;

/** WHAT 的正式值；kind 不依赖自然语言分类，description 不得夹带未被该种类证明的承诺。 */
public record VerificationCriterion(Kind kind, String description) {
    public VerificationCriterion {
        if (kind == null || description == null || description.isBlank()) {
            throw new IllegalArgumentException("Verification criterion kind and description are required");
        }
        // 当前技术种类使用明确的人类可读说明，防止把任意业务承诺伪装成技术 WHAT。
        if (kind != Kind.UNSUPPORTED && !kind.description().equals(description)) {
            throw new IllegalArgumentException("Technical criterion description exceeds its supported meaning");
        }
    }

    public static VerificationCriterion technical(Kind kind) {
        return new VerificationCriterion(kind, kind.description());
    }

    public static VerificationCriterion unsupported(String description) {
        return new VerificationCriterion(Kind.UNSUPPORTED, description);
    }

    public boolean supportedBy(String action, String target) {
        if (action == null || target == null) {
            return false;
        }
        boolean focused = action.equals("FOCUSED_TEST")
                && target.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*(#[A-Za-z_][A-Za-z0-9_]*)?");
        return switch (kind) {
            case BUILD_SUCCEEDS -> action.equals("BUILD") && target.isEmpty();
            case FULL_TEST_SUITE_PASSES -> action.equals("TEST") && target.isEmpty();
            case SELECTED_TESTS_PASS -> focused || (action.equals("TEST") && target.isEmpty());
            case DIFF_CHECK_PASSES -> action.equals("DIFF_CHECK") && target.isEmpty();
            case UNSUPPORTED -> false;
        };
    }

    public enum Kind {
        BUILD_SUCCEEDS("项目构建成功"),
        FULL_TEST_SUITE_PASSES("项目完整自动化测试套件通过"),
        SELECTED_TESTS_PASS("本步骤选择的自动化测试通过"),
        DIFF_CHECK_PASSES("本次代码差异无空白错误或冲突标记"),
        UNSUPPORTED("");

        private final String description;
        Kind(String description) { this.description = description; }
        public String description() { return description; }
    }
}
