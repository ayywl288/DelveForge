package com.ayywl.delveforge.application.port.persistence;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import java.util.Optional;

/** Metadata reads only. Initial writes belong to the atomic activation commit. */
public interface WorkingCopyRepository {
    Optional<WorkingCopy> findById(WorkingCopyId id);
}
