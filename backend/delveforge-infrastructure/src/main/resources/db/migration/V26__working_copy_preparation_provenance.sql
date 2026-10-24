-- 历史行保留 NULL：读取时解释为 P=A，不更新可能仍被占用的 WorkingCopy。
ALTER TABLE working_copy ADD COLUMN prepared_revision TEXT;
ALTER TABLE working_copy ADD COLUMN preparation_json TEXT;

CREATE TRIGGER working_copy_preparation_immutable
BEFORE UPDATE OF source_revision, prepared_revision, preparation_json ON working_copy
WHEN NEW.source_revision IS NOT OLD.source_revision
  OR NEW.prepared_revision IS NOT OLD.prepared_revision
  OR NEW.preparation_json IS NOT OLD.preparation_json
BEGIN
    SELECT RAISE(ABORT, 'WorkingCopy preparation provenance is immutable');
END;

CREATE TRIGGER working_copy_preparation_insert
BEFORE INSERT ON working_copy
WHEN (NEW.prepared_revision IS NULL) <> (NEW.preparation_json IS NULL)
 OR NEW.prepared_revision IS NOT NULL AND (
    NEW.preparation_json IS NULL OR NOT json_valid(NEW.preparation_json)
    OR json_extract(NEW.preparation_json, '$.originalRevision') IS NOT NEW.source_revision
    OR json_extract(NEW.preparation_json, '$.preparedRevision') IS NOT NEW.prepared_revision
    OR (NEW.status = 'READY' AND (NEW.current_revision IS NOT NEW.prepared_revision
        OR NEW.last_verified_revision IS NOT NEW.prepared_revision)))
BEGIN
    SELECT RAISE(ABORT, 'WorkingCopy preparation provenance does not match its baseline');
END;
