-- 稳定作用域不由路径、画像或创建时间推断；上下文导航列表不是授权事实。
ALTER TABLE project_flow_context ADD COLUMN flow_id TEXT;
ALTER TABLE project_flow_context ADD COLUMN previous_context_id TEXT REFERENCES project_flow_context(id);
ALTER TABLE product_direction ADD COLUMN flow_id TEXT;
ALTER TABLE evolution_plan ADD COLUMN flow_id TEXT;

UPDATE project_flow_context SET flow_id = id;

-- 仅一个上下文明确引用、且画像身份一致的方向可以归属该上下文。
WITH owners AS (
    SELECT c.id AS context_id, j.value AS direction_id
    FROM project_flow_context c, json_each(c.direction_ids) j
    UNION
    SELECT c.id, p.product_direction_id
    FROM project_flow_context c JOIN evolution_plan p ON p.id = c.plan_id
), unique_owner AS (
    SELECT direction_id, MIN(context_id) AS context_id FROM owners
    GROUP BY direction_id HAVING COUNT(DISTINCT context_id) = 1
)
UPDATE product_direction SET flow_id = (
    SELECT u.context_id FROM unique_owner u JOIN project_flow_context c ON c.id = u.context_id
    WHERE u.direction_id = product_direction.id AND c.user_profile_id = product_direction.user_profile_id
);

-- 无上下文的旧 Plan 只沿 Plan -> Direction 的真实引用建立独立历史作用域。
UPDATE product_direction SET flow_id = 'legacy-' || id
WHERE flow_id IS NULL AND EXISTS (SELECT 1 FROM evolution_plan p WHERE p.product_direction_id = product_direction.id)
    AND NOT EXISTS (SELECT 1 FROM project_flow_context c, json_each(c.direction_ids) j
                    WHERE j.value = product_direction.id)
    AND NOT EXISTS (SELECT 1 FROM project_flow_context c JOIN evolution_plan p ON p.id = c.plan_id
                    WHERE p.product_direction_id = product_direction.id);
UPDATE evolution_plan SET flow_id = (SELECT d.flow_id FROM product_direction d WHERE d.id = product_direction_id);

DROP INDEX ux_product_direction_current_selected;
CREATE UNIQUE INDEX ux_direction_selected_per_flow ON product_direction(flow_id)
    WHERE status = 'SELECTED' AND flow_id IS NOT NULL;
CREATE INDEX ix_direction_flow ON product_direction(flow_id, id);
CREATE INDEX ix_plan_flow ON evolution_plan(flow_id, id);
CREATE INDEX ix_context_flow ON project_flow_context(flow_id, id);
CREATE UNIQUE INDEX ux_context_successor ON project_flow_context(previous_context_id)
    WHERE previous_context_id IS NOT NULL;

CREATE TRIGGER direction_flow_immutable BEFORE UPDATE OF flow_id ON product_direction
WHEN NEW.flow_id IS NOT OLD.flow_id BEGIN SELECT RAISE(ABORT, 'immutable direction flow'); END;
CREATE TRIGGER plan_flow_immutable BEFORE UPDATE OF flow_id ON evolution_plan
WHEN NEW.flow_id IS NOT OLD.flow_id BEGIN SELECT RAISE(ABORT, 'immutable plan flow'); END;
CREATE TRIGGER context_flow_immutable BEFORE UPDATE OF flow_id, previous_context_id ON project_flow_context
WHEN NEW.flow_id IS NOT OLD.flow_id OR NEW.previous_context_id IS NOT OLD.previous_context_id
BEGIN SELECT RAISE(ABORT, 'immutable context flow'); END;
CREATE TRIGGER unknown_direction_not_selectable BEFORE UPDATE OF status ON product_direction
WHEN NEW.status = 'SELECTED' AND NEW.flow_id IS NULL
BEGIN SELECT RAISE(ABORT, 'unknown direction flow'); END;
