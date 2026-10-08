package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.EvolutionLifecycleConflictException;
import com.ayywl.delveforge.application.port.persistence.StepExecutionCommitPort;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.infrastructure.persistence.workingcopy.WorkingCopyMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 原子保存 Step 与 WorkingCopy 的完整检查点；条件更新失配则回滚本事务，不补偿 Git 操作。 */
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

        // 重放 Domain 操作验证完整转移，不能凭调用方给出的新状态直接写入。
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
                || !before.sourceRevision().equals(after.sourceRevision())
                || !before.location().equals(after.location())
                || check.status() != next.status() || !Objects.equals(check.baselineRevision(), next.baselineRevision())
                || !Objects.equals(check.executionResult(), next.executionResult())
                || !Objects.equals(check.verificationResult(), next.verificationResult())
                || checkedCopy.status() != after.status()
                || !checkedCopy.currentRevision().equals(after.currentRevision())
                || !checkedCopy.lastVerifiedRevision().equals(after.lastVerifiedRevision())) {
            throw new EvolutionPlanStateException("Checkpoint is not the complete domain transition");
        }

        // 两项写入共同受事务保护，任一旧依据不匹配都不能暴露半个检查点。
        if (steps.advanceCheckpoint(expected, id, before, asset, asset.licenseInfo().orElse(null), old, next,
                StepResultJson.write(old.executionResult()), StepResultJson.write(old.verificationResult()),
                StepResultJson.write(next.executionResult()), StepResultJson.write(next.verificationResult())) != 1
                || copies.advanceCheckpoint(before, after) != 1) {
            throw new EvolutionLifecycleConflictException("Execution checkpoint basis changed before commit");
        }
    }
}
