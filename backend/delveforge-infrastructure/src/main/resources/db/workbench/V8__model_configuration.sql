ALTER TABLE conversation ADD COLUMN model_choice TEXT;
ALTER TABLE run ADD COLUMN model_config TEXT;
ALTER TABLE record ADD COLUMN model_message TEXT;
ALTER TABLE model_turn ADD COLUMN model_message TEXT;

CREATE TRIGGER immutable_run_model BEFORE UPDATE OF model_config ON run
WHEN OLD.model_config IS NOT NULL AND NEW.model_config IS NOT OLD.model_config
BEGIN
    SELECT RAISE(ABORT, 'Run model configuration is immutable');
END;

CREATE TABLE workbench_provider (
    provider TEXT PRIMARY KEY,
    preset TEXT NOT NULL,
    workspace TEXT NOT NULL,
    credential_ref TEXT NOT NULL UNIQUE,
    ciphertext BLOB NOT NULL
);

CREATE TABLE model_validation (
    credential_ref TEXT NOT NULL,
    model TEXT NOT NULL,
    effort TEXT NOT NULL,
    compatible INTEGER NOT NULL CHECK (compatible IN (0,1)),
    PRIMARY KEY (credential_ref, model, effort)
);
