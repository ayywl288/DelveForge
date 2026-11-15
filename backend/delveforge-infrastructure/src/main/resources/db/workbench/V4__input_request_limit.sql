ALTER TABLE input ADD COLUMN request_limit INTEGER CHECK (request_limit BETWEEN 1 AND 10000);
