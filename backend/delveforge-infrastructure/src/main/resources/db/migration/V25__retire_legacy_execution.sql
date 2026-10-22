-- 保留旧执行原始事实，不尝试把旧 M4/M5 成功伪装为新 Delivery 接受。
CREATE TABLE legacy_evolution_execution AS
SELECT s.* FROM evolution_step s
WHERE s.delivery_revision IS NULL AND (s.status NOT IN ('PENDING_CONFIRMATION','READY','CANCELLED')
    OR s.execution_result IS NOT NULL OR s.verification_result IS NOT NULL
    OR s.execution_failure_result IS NOT NULL OR s.rollback_authorization IS NOT NULL
    OR s.repair_authorizations <> '[]' OR s.retry_authorizations <> '[]');

UPDATE evolution_plan SET status='SUPERSEDED'
WHERE id IN (SELECT plan_id FROM legacy_evolution_execution);
UPDATE evolution_step SET status='CANCELLED',baseline_revision=NULL
WHERE plan_id IN (SELECT plan_id FROM legacy_evolution_execution);

-- 原始 SoftwareAsset、WorkingCopy 文件、M1/M2/M3 事实和 agent_candidate 均不删除。
-- 旧列与 Criterion section 留作只读历史，现行代码不再消费旧执行 JSON。
