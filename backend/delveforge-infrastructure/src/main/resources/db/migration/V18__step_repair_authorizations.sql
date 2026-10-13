-- 旧检查点只初始化为空历史，不制造 Repair 授权，也不改变已有生命周期或结果。
ALTER TABLE evolution_step ADD COLUMN repair_authorizations TEXT NOT NULL DEFAULT '[]';
