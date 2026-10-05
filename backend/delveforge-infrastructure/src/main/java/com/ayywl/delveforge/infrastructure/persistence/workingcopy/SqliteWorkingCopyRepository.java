package com.ayywl.delveforge.infrastructure.persistence.workingcopy;

import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evolution.*;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class SqliteWorkingCopyRepository implements WorkingCopyRepository {
    private final WorkingCopyMapper mapper;

    public SqliteWorkingCopyRepository(WorkingCopyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<WorkingCopy> findById(WorkingCopyId id) {
        WorkingCopyDO row = mapper.selectById(id.value());
        return row == null ? Optional.empty() : Optional.of(WorkingCopy.reconstitute(id,
                new SoftwareAssetId(row.getSourceAssetId()), row.getSourceRevision(), row.getLocation(),
                row.getCurrentRevision(), row.getLastVerifiedRevision(), WorkingCopyStatus.valueOf(row.getStatus())));
    }
}
