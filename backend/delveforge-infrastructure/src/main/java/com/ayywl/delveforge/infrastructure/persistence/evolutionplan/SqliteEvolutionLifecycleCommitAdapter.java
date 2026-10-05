package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.infrastructure.persistence.workingcopy.*;
import java.util.List;
import java.sql.SQLException;
import java.util.function.Consumer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

/** One SQL transaction per lifecycle commit, including stale-basis guards.
 * Transaction manager is configured to roll back on commit failure.
 * Content/history rows are never replaced by lifecycle writes.
 */
@Repository
public class SqliteEvolutionLifecycleCommitAdapter implements EvolutionLifecycleCommitPort {
    private final EvolutionPlanMapper plans;
    private final WorkingCopyMapper copies;
    private final ProductDirectionRepository directions;
    private final TransactionTemplate transaction;
    public SqliteEvolutionLifecycleCommitAdapter(EvolutionPlanMapper plans, WorkingCopyMapper copies,
            ProductDirectionRepository directions, PlatformTransactionManager manager) {
        this.plans = plans; this.copies = copies; this.directions = directions;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override public void commitActivation(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset basis) {
        if (plan.status() != EvolutionPlanStatus.ACTIVE || copy.status() != WorkingCopyStatus.READY
                || !copy.id().value().equals(plan.workingCopyId()) || !copy.sourceAssetId().equals(plan.baseAssetId())
                || !basis.id().equals(plan.baseAssetId()))
            throw new EvolutionLifecycleConflictException("Activation commit requires a coherent domain candidate");
        commit(status -> {
            var row = new WorkingCopyDO();
            row.setId(copy.id().value()); row.setSourceAssetId(copy.sourceAssetId().value());
            row.setSourceRevision(copy.sourceRevision()); row.setLocation(copy.location());
            row.setCurrentRevision(copy.currentRevision()); row.setLastVerifiedRevision(copy.lastVerifiedRevision());
            row.setStatus(copy.status().name());
            copies.insert(row);
            if (plans.activateAtBasis(plan, copy, basis, basis.licenseInfo().orElse(null)) != 1)
                throw new EvolutionLifecycleConflictException("Activation basis changed before commit");
        });
    }

    @Override public void commitDirectionSelection(List<ProductDirectionTransition> transitions,
            List<EvolutionPlanTransition> planTransitions) {
        commit(status -> {
            for (var transition : planTransitions) {
                if (plans.transitionAtBasis(transition) != 1)
                    throw new EvolutionLifecycleConflictException("Historical Plan lifecycle changed before direction switch");
            }
            directions.saveTransitions(transitions);
            for (var transition : transitions) {
                if (transition.direction().status() == ProductDirectionStatus.SUPERSEDED
                        && plans.countContinuingPlans(transition.direction().id().value()) != 0)
                    throw new EvolutionLifecycleConflictException("A new Plan appeared during direction switch");
            }
        });
    }

    private void commit(Consumer<TransactionStatus> operation) {
        try { transaction.executeWithoutResult(operation); }
        catch (DataAccessException | TransactionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    int primaryCode = sql.getErrorCode() & 0xff;
                    if (primaryCode == 5 || primaryCode == 6) // SQLITE_BUSY / SQLITE_LOCKED, including extended codes
                        throw new EvolutionLifecycleConflictException("Concurrent lifecycle write/commit conflict", failure);
                }
            }
            throw failure;
        }
    }
}
