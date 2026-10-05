package com.ayywl.delveforge.application.evolution.provisioning;

import com.ayywl.delveforge.domain.evolution.WorkingCopyId;

public final class WorkingCopyNotFoundException extends RuntimeException {
    public WorkingCopyNotFoundException(WorkingCopyId id) {
        super("Working Copy not found: " + id.value());
    }
}
