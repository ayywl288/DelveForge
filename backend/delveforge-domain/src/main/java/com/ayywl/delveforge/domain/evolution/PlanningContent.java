package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/**
 * 集中校验 Evolution Planning 所拥有的工程内容。
 */
final class PlanningContent {
    private PlanningContent() {}
    static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static List<String> section(List<String> values, String field, boolean required) {
        if (values == null || (required && values.isEmpty())) {
            throw new IllegalArgumentException(field + " is required");
        }
        values.forEach(value -> text(value, field));
        return List.copyOf(values);
    }
}
