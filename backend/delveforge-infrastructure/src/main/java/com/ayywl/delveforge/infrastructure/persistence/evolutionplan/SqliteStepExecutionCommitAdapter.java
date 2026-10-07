package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.infrastructure.persistence.workingcopy.WorkingCopyMapper;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SqliteStepExecutionCommitAdapter implements StepExecutionCommitPort {
    private final EvolutionStepMapper steps;
    private final WorkingCopyMapper copies;
    public SqliteStepExecutionCommitAdapter(EvolutionStepMapper steps, WorkingCopyMapper copies) {
        this.steps = steps;
        this.copies = copies;
    }

    @Override
    @Transactional
    public void commit(EvolutionPlan expected, EvolutionStepId id, WorkingCopy before, SoftwareAsset asset,
            EvolutionPlan updated, WorkingCopy after) {
        new AssetUsagePolicy().requireEvolutionBaseAllowed(asset);
        EvolutionPlan checked = expected.copy();
        WorkingCopy checkedCopy = before.copy();
        EvolutionStep old = expected.step(id);
        EvolutionStep next = updated.step(id);
        if (old.status() == EvolutionStepStatus.READY && next.status() == EvolutionStepStatus.EXECUTING) {
            checked.startStep(id, checkedCopy, new StepExecutionPolicy());
        } else if (old.status() == EvolutionStepStatus.EXECUTING && next.status() == EvolutionStepStatus.VERIFYING) {
            checked.recordExecution(id, checkedCopy, next.executionResult());
        } else if (old.status() == EvolutionStepStatus.VERIFYING && next.status() == EvolutionStepStatus.SUCCEEDED) {
            checked.succeedStep(id, checkedCopy, next.verificationResult());
        } else {
            throw new EvolutionPlanStateException("Unsupported M4 checkpoint transition");
        }
        EvolutionStep check = checked.step(id);
        if (!expected.id().equals(updated.id()) || expected.status() != updated.status()
                || !Objects.equals(expected.workingCopyId(), updated.workingCopyId())
                || !before.id().equals(after.id()) || !before.sourceAssetId().equals(after.sourceAssetId())
                || !before.sourceRevision().equals(after.sourceRevision()) || !before.location().equals(after.location())
                || check.status() != next.status() || !Objects.equals(check.baselineRevision(), next.baselineRevision())
                || !Objects.equals(check.executionResult(), next.executionResult())
                || !Objects.equals(check.verificationResult(), next.verificationResult())
                || checkedCopy.status() != after.status() || !checkedCopy.currentRevision().equals(after.currentRevision())
                || !checkedCopy.lastVerifiedRevision().equals(after.lastVerifiedRevision())) {
            throw new EvolutionPlanStateException("Checkpoint is not the complete domain transition");
        }
        if (steps.advanceCheckpoint(expected, id, before, asset, asset.licenseInfo().orElse(null), old, next,
                StepResultJson.write(old.executionResult()), StepResultJson.write(old.verificationResult()),
                StepResultJson.write(next.executionResult()), StepResultJson.write(next.verificationResult())) != 1
                || copies.advanceCheckpoint(before, after) != 1) {
            throw new EvolutionLifecycleConflictException("Execution checkpoint basis changed before commit");
        }
    }
}
