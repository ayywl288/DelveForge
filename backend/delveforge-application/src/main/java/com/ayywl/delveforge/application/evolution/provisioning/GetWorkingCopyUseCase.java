package com.ayywl.delveforge.application.evolution.provisioning;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import java.util.Objects;

public final class GetWorkingCopyUseCase {
    private final WorkingCopyRepository copies;
    public GetWorkingCopyUseCase(WorkingCopyRepository copies) { this.copies = Objects.requireNonNull(copies); }
    public WorkingCopy get(WorkingCopyId id) {
        return copies.findById(Objects.requireNonNull(id)).orElseThrow(() -> new WorkingCopyNotFoundException(id));
    }
}
