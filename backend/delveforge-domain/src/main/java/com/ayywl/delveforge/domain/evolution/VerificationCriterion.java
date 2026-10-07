package com.ayywl.delveforge.domain.evolution;

/** M4 当前仅自动接受明确的技术标准；自然语言业务标准没有可信证据适配器时拒绝。 */
public final class VerificationCriterion {
    private VerificationCriterion() {}

    public static boolean supportedBy(String criterion, String action, String target) {
        if (criterion == null || action == null || target == null) {
            return false;
        }
        return switch (action) {
            case "BUILD", "TEST", "DIFF_CHECK" -> target.isEmpty() && criterion.equals(action + " passes");
            case "FOCUSED_TEST" -> target.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*(#[A-Za-z_][A-Za-z0-9_]*)?")
                    && criterion.equals("FOCUSED_TEST " + target + " passes");
            default -> false;
        };
    }
}
