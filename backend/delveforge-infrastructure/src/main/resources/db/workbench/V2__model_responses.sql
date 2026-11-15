CREATE TABLE model_turn (
    seq INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES run(id),
    text TEXT, calls TEXT NOT NULL, reasoning TEXT, accepted INTEGER NOT NULL
);
