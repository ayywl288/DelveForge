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
import com.ayywl.delveforge.domain.evolution.RepairAuthorization;
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
        // 重放 Domain 操作验证完整转移，不能凭调用方给出的新状态直接写入。
        EvolutionPlan checked = expected.copy();
        WorkingCopy checkedCopy = before.copy();
        EvolutionStep old = expected.step(id);
        EvolutionStep next = updated.step(id);
        boolean blockingRollback = old.status() == EvolutionStepStatus.ROLLING_BACK
                && next.status() == EvolutionStepStatus.RECOVERY_REQUIRED;
        if (!blockingRollback) {
            new AssetUsagePolicy().requireEvolutionBaseAllowed(asset);
        }
        if (old.status() == EvolutionStepStatus.READY && next.status() == EvolutionStepStatus.EXECUTING) {
            checked.startStep(id, checkedCopy, new StepExecutionPolicy());
        } else if (old.status() == EvolutionStepStatus.VERIFICATION_FAILED
                && next.status() == EvolutionStepStatus.EXECUTING && !next.repairAuthorizations().isEmpty()) {
            RepairAuthorization authorization = next.repairAuthorizations().getLast();
            checked.authorizeStepRepair(id, checkedCopy, new StepExecutionPolicy(), authorization.id(),
                    authorization.candidateRevision(), authorization.authorizedAt());
        } else if (old.status() == EvolutionStepStatus.EXECUTING && next.status() == EvolutionStepStatus.VERIFYING) {
            checked.recordExecution(id, checkedCopy, next.executionResult());
        } else if (old.status() == EvolutionStepStatus.VERIFYING && next.status() == EvolutionStepStatus.SUCCEEDED) {
            checked.succeedStep(id, checkedCopy, next.verificationResult());
        } else if (old.status() == EvolutionStepStatus.VERIFYING
                && next.status() == EvolutionStepStatus.VERIFICATION_FAILED) {
            checked.failStepVerification(id, checkedCopy, next.verificationResult());
        } else if (old.status() == EvolutionStepStatus.VERIFICATION_FAILED
                && next.status() == EvolutionStepStatus.ROLLING_BACK && next.rollbackAuthorization() != null) {
            var authorization = next.rollbackAuthorization();
            checked.authorizeStepRollback(id, checkedCopy, new StepExecutionPolicy(), authorization.id(),
                    authorization.candidateRevision(), authorization.baselineRevision(), authorization.authorizedAt());
        } else if (old.status() == EvolutionStepStatus.ROLLING_BACK
                && (next.status() == EvolutionStepStatus.FAILED || blockingRollback)) {
            checked.finishStepRollback(id, checkedCopy, next.rollbackResult());
        } else {
            throw new EvolutionPlanStateException("Unsupported execution checkpoint transition");
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
                || !check.repairAuthorizations().equals(next.repairAuthorizations())
                || !Objects.equals(check.rollbackAuthorization(), next.rollbackAuthorization())
                || !Objects.equals(check.rollbackResult(), next.rollbackResult())
                || !check.goal().equals(next.goal()) || !check.scope().equals(next.scope())
                || !check.plannedChanges().equals(next.plannedChanges())
                || !check.verificationCriteria().equals(next.verificationCriteria())
                || checkedCopy.status() != after.status()
                || !checkedCopy.currentRevision().equals(after.currentRevision())
                || !checkedCopy.lastVerifiedRevision().equals(after.lastVerifiedRevision())) {
            throw new EvolutionPlanStateException("Checkpoint is not the complete domain transition");
        }

        // 两项写入共同受事务保护，任一旧依据不匹配都不能暴露半个检查点。
        int changed = blockingRollback
                ? steps.blockRollback(expected, id, before, old, StepResultJson.write(old.executionResult()),
                        StepResultJson.write(old.verificationResult()), StepResultJson.write(old.repairAuthorizations()),
                        StepResultJson.write(old.rollbackAuthorization()), StepResultJson.write(next.rollbackResult()))
                : steps.advanceCheckpoint(expected, id, before, asset, asset.licenseInfo().orElse(null), old, next,
                        StepResultJson.write(old.executionResult()), StepResultJson.write(old.verificationResult()),
                        StepResultJson.write(next.executionResult()), StepResultJson.write(next.verificationResult()),
                        StepResultJson.write(old.repairAuthorizations()), StepResultJson.write(next.repairAuthorizations()),
                        StepResultJson.write(old.rollbackAuthorization()), StepResultJson.write(old.rollbackResult()),
                        StepResultJson.write(next.rollbackAuthorization()), StepResultJson.write(next.rollbackResult()));
        if (changed != 1 || copies.advanceCheckpoint(before, after) != 1) {
            throw new EvolutionLifecycleConflictException("Execution checkpoint basis changed before commit");
        }
    }
}
