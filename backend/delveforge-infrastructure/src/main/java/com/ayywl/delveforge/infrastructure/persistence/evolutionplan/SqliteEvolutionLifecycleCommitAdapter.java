package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.infrastructure.persistence.workingcopy.*;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每次生命周期提交使用一个 SQL 事务，同时核对读取依据是否过期。
 * 事务管理器配置为提交失败时回滚；生命周期写入不会替换内容或历史记录。
 */
@Repository
public class SqliteEvolutionLifecycleCommitAdapter implements EvolutionLifecycleCommitPort {
    private final EvolutionPlanMapper plans;
    private final WorkingCopyMapper copies;
    private final ProductDirectionRepository directions;
    private final TransactionTemplate transaction;

    public SqliteEvolutionLifecycleCommitAdapter(EvolutionPlanMapper plans, WorkingCopyMapper copies,
            ProductDirectionRepository directions, PlatformTransactionManager manager) {
        this.plans = plans;
        this.copies = copies;
        this.directions = directions;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void commitActivation(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset basis) {
        if (plan.status() != EvolutionPlanStatus.ACTIVE || copy.status() != WorkingCopyStatus.READY
                || !copy.id().value().equals(plan.workingCopyId()) || !copy.sourceAssetId().equals(plan.baseAssetId())
                || !basis.id().equals(plan.baseAssetId())) {
            throw new EvolutionLifecycleConflictException("Activation commit requires a coherent domain candidate");
        }

        commit(status -> {
            var row = new WorkingCopyDO();
            row.setId(copy.id().value());
            row.setSourceAssetId(copy.sourceAssetId().value());
            row.setSourceRevision(copy.sourceRevision());
            row.setLocation(copy.location());
            row.setCurrentRevision(copy.currentRevision());
            row.setLastVerifiedRevision(copy.lastVerifiedRevision());
            row.setStatus(copy.status().name());

            // READY 元数据与 ACTIVE 绑定共用事务，条件更新失败时前者也必须回滚。
            copies.insert(row);
            if (plans.activateAtBasis(plan, copy, basis, basis.licenseInfo().orElse(null)) != 1) {
                throw new EvolutionLifecycleConflictException("Activation basis changed before commit");
            }
        });
    }

    @Override
    public void commitDirectionSelection(List<ProductDirectionTransition> transitions,
            List<EvolutionPlanTransition> planTransitions) {
        commit(status -> {
            for (EvolutionPlanTransition transition : planTransitions) {
                if (plans.transitionAtBasis(transition) != 1) {
                    throw new EvolutionLifecycleConflictException("Historical Plan lifecycle changed before direction switch");
                }
            }

            directions.saveTransitions(transitions);

            // 捕获加载旧 Plan 列表之后新增的计划，避免方向切换遗漏仍可继续的历史 Plan。
            for (ProductDirectionTransition transition : transitions) {
                if (transition.direction().status() == ProductDirectionStatus.SUPERSEDED
                        && plans.countContinuingPlans(transition.direction().id().value()) != 0) {
                    throw new EvolutionLifecycleConflictException("A new Plan appeared during direction switch");
                }
            }
        });
    }

    private void commit(Consumer<TransactionStatus> operation) {
        try {
            transaction.executeWithoutResult(operation);
        } catch (DataAccessException | TransactionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    int primaryCode = sql.getErrorCode() & 0xff;
                    // 仅看主错误码，兼容 SQLITE_BUSY / SQLITE_LOCKED 的扩展码。
                    if (primaryCode == 5 || primaryCode == 6) {
                        throw new EvolutionLifecycleConflictException("Concurrent lifecycle write/commit conflict", failure);
                    }
                }
            }
            throw failure;
        }
    }
}
