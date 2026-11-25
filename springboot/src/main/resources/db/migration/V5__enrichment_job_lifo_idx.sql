-- Align the partial claim index with LIFO (most-recently-requested first) job claiming.
DROP INDEX IF EXISTS enrichment_job_claim_idx;
CREATE INDEX enrichment_job_claim_idx
    ON enrichment_job (created_at DESC, id DESC)
    WHERE status IN ('pending', 'failed');
