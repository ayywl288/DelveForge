-- 删除当前 MVP 的自由文本 Step Preconditions 契约，不把旧条件默认为已满足。
-- 保留旧文本、Step 检查点和 WorkingCopy；有旧条件的可推进 Plan 必须重新规划。
UPDATE evolution_plan
SET status = 'SUPERSEDED'
WHERE status IN ('PROPOSED', 'ACTIVE')
  AND EXISTS (
    SELECT 1 FROM evolution_step step
    JOIN evolution_step_section_item item ON item.step_id = step.id
    WHERE step.plan_id = evolution_plan.id AND item.section = 'preconditions'
  );
