package com.ayywl.delveforge.domain.evolution;

public record WorkingCopyId(String value) {
    public WorkingCopyId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WorkingCopyId must not be blank");
        }
    }
}
