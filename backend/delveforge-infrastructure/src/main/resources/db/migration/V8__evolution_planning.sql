-- Task 1: one proposed planning Aggregate, ordered steps and traceable evidence.
-- No Working Copy, execution or verification is created here.
-- As in existing migrations, cross-Aggregate references are identities, without
-- relying on SQLite's disabled-by-default foreign_keys pragma.
CREATE TABLE evolution_plan (
    id TEXT NOT NULL PRIMARY KEY,
    product_direction_id TEXT NOT NULL,
    base_asset_id TEXT NOT NULL,
    base_repository_profile_id TEXT NOT NULL,
    working_copy_id TEXT,
    current_summary TEXT NOT NULL,
    target_problem TEXT NOT NULL,
    target_product TEXT NOT NULL,
    target_differentiation TEXT NOT NULL,
    status TEXT NOT NULL
);
CREATE INDEX evolution_plan_direction ON evolution_plan(product_direction_id);

CREATE TABLE evolution_plan_section_item (
    plan_id TEXT NOT NULL,
    section TEXT NOT NULL,
    position INTEGER NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY(plan_id, section, position)
);

CREATE TABLE evolution_step (
    id TEXT NOT NULL PRIMARY KEY,
    plan_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    goal TEXT NOT NULL,
    scope TEXT NOT NULL,
    status TEXT NOT NULL,
    baseline_revision TEXT,
    UNIQUE(plan_id, position)
);

CREATE TABLE evolution_step_section_item (
    step_id TEXT NOT NULL,
    section TEXT NOT NULL,
    position INTEGER NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY(step_id, section, position)
);

CREATE TABLE evolution_plan_evidence (
    plan_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    source_type TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    claim TEXT NOT NULL,
    confidence REAL,
    confirmed INTEGER NOT NULL CHECK(confirmed IN (0, 1)),
    origin_kind TEXT NOT NULL,
    origin_user_profile_id TEXT,
    origin_user_profile_revision INTEGER,
    origin_repository_profile_id TEXT,
    PRIMARY KEY(plan_id, position),
    CHECK (
        (origin_kind = 'userProfile'
         AND origin_user_profile_id IS NOT NULL
         AND origin_user_profile_revision IS NOT NULL
         AND origin_repository_profile_id IS NULL)
        OR
        (origin_kind = 'repositoryProfile'
         AND origin_repository_profile_id IS NOT NULL
         AND origin_user_profile_id IS NULL
         AND origin_user_profile_revision IS NULL)
    )
);
