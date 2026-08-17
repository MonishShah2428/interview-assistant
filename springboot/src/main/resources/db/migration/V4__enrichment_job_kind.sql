ALTER TABLE enrichment_job ADD COLUMN kind TEXT NOT NULL DEFAULT 'initial'
    CHECK (kind IN ('initial', 'resource_refresh'));
