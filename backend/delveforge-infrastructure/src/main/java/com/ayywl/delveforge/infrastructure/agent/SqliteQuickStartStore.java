package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.quickstart.QuickStart;
import com.ayywl.delveforge.application.quickstart.QuickStartStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/** 只读取已接受交付，指南持久化不接触业务聚合与工作副本。 */
public final class SqliteQuickStartStore implements QuickStartStore {
    private final DataSource source;

    public SqliteQuickStartStore(DataSource source) {
        this.source = source;
    }

    @Override
    public Optional<Basis> finalDelivery(String planId) {
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement("""
                SELECT d.candidate_id,d.revision,d.location,a.location FROM evolution_plan p
                JOIN working_copy w ON w.id=p.working_copy_id
                JOIN software_asset a ON a.id=p.base_asset_id
                JOIN evolution_step s ON s.plan_id=p.id
                JOIN candidate_delivery d ON d.step_id=s.id AND d.plan_id=p.id
                WHERE p.id=? AND p.status='COMPLETED' AND w.status='CLOSED'
                AND a.read_permission=1 AND a.usage_authorization='ALLOWED'
                AND d.state='COMPLETED' AND d.revision=w.current_revision AND d.revision=w.last_verified_revision
                AND s.delivery_revision=d.revision AND s.status='SUCCEEDED'
                AND NOT EXISTS (SELECT 1 FROM evolution_step other WHERE other.plan_id=p.id
                    AND (other.status<>'SUCCEEDED' OR NOT EXISTS (SELECT 1 FROM candidate_delivery accepted
                        WHERE accepted.plan_id=p.id AND accepted.step_id=other.id AND accepted.state='COMPLETED'
                        AND accepted.revision=other.delivery_revision)))
                ORDER BY s.position DESC LIMIT 1
                """)) {
            statement.setString(1, planId);
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(new Basis(planId, row.getString(1), row.getString(2),
                        row.getString(3), row.getString(4)))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read final Delivery", failure);
        }
    }

    @Override
    public Optional<QuickStart> find(String planId) {
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement("""
                SELECT q.*, EXISTS (SELECT 1 FROM candidate_delivery later
                    JOIN evolution_plan newer ON newer.id=later.plan_id
                    JOIN candidate_delivery original ON original.candidate_id=q.candidate_id
                    WHERE newer.flow_id=owner.flow_id AND newer.id<>q.plan_id
                    AND later.state='COMPLETED' AND later.created_at>original.created_at) AS stale
                FROM plan_quick_start q JOIN evolution_plan owner ON owner.id=q.plan_id
                JOIN evolution_plan requested ON requested.id=?
                WHERE q.plan_id=requested.id OR owner.flow_id=requested.flow_id
                ORDER BY (q.plan_id=requested.id) DESC,q.updated_at DESC LIMIT 1
                """)) {
            statement.setString(1, planId);
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(new QuickStart(row.getString("plan_id"), row.getString("candidate_id"),
                        row.getString("revision"), row.getString("status"), row.getString("markdown"),
                        row.getString("reason"), Instant.parse(row.getString("updated_at")), row.getBoolean("stale")))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot read Quick Start", failure);
        }
    }

    @Override
    public void save(QuickStart guide) {
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO plan_quick_start(plan_id,candidate_id,revision,status,markdown,reason,updated_at)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(plan_id) DO UPDATE SET candidate_id=excluded.candidate_id,
                revision=excluded.revision,status=excluded.status,markdown=excluded.markdown,
                reason=excluded.reason,updated_at=excluded.updated_at
                """)) {
            SqliteAgentStore.bind(statement, guide.planId(), guide.candidateId(), guide.revision(), guide.status(),
                    guide.markdown(), guide.reason(), guide.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot save Quick Start", failure);
        }
    }

    @Override
    public void interruptGenerating() {
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement("""
                UPDATE plan_quick_start SET status='UNAVAILABLE',reason='GENERATION_INTERRUPTED'
                WHERE status='GENERATING'
                """)) {
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new AgentException("PERSISTENCE_FAILURE", "Cannot reconcile Quick Start", failure);
        }
    }
}
