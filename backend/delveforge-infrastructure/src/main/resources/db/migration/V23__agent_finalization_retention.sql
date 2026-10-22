-- V22 已用于本地运行与独立验收，不重写其校验和。
-- 补回旧版本在最终化终态提交后、release 前中断的保留意图；已成功冻结或确认无变化的 Run 无需保留。
UPDATE agent_run SET retained = 1
WHERE occupied = 1 AND state <> 'SUCCEEDED'
    AND (reason IS NULL OR reason <> 'NO_CHANGES')
    AND (state = 'FINALIZING' OR EXISTS (
        SELECT 1 FROM agent_event e WHERE e.run_id = agent_run.id AND e.kind = 'FINALIZING'));
