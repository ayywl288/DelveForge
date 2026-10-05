-- Initial Working Copy metadata is inserted only with the Plan activation transaction.
-- Git objects and worktree files remain on disk; no visible CREATING row is needed in M3.
CREATE TABLE working_copy (
    id TEXT NOT NULL PRIMARY KEY,
    source_asset_id TEXT NOT NULL,
    source_revision TEXT NOT NULL,
    location TEXT NOT NULL,
    current_revision TEXT NOT NULL,
    last_verified_revision TEXT NOT NULL,
    status TEXT NOT NULL
);

-- M3 implementation choice: never share an initially prepared environment between Plans.
-- Historical bindings remain present; this does not introduce workspace reuse semantics.
CREATE UNIQUE INDEX evolution_plan_working_copy
    ON evolution_plan(working_copy_id) WHERE working_copy_id IS NOT NULL;
