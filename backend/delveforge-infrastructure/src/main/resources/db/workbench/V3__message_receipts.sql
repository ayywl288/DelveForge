ALTER TABLE input ADD COLUMN acknowledged_run TEXT REFERENCES run(id);
