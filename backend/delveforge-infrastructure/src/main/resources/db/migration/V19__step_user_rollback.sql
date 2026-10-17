-- 只增加本 Slice 的授权及结果槽，不改写已有状态、Repair 历史或验证证据。
ALTER TABLE evolution_step ADD COLUMN rollback_authorization TEXT;
ALTER TABLE evolution_step ADD COLUMN rollback_result TEXT;
