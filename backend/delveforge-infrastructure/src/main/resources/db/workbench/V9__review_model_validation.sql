-- V8 未区分单次未遵循指令、账户问题及能力拒绝。旧负结果可重新验证。
DELETE FROM model_validation WHERE compatible = 0;

-- 宿主环境引用可在没有数据库凭据行时使用，保留其正向验证。
DELETE FROM model_validation
WHERE credential_ref NOT LIKE 'env-%'
  AND NOT EXISTS (SELECT 1 FROM workbench_provider p WHERE p.credential_ref = model_validation.credential_ref);
