-- Postgres-backed job table for the enrichment worker: SELECT ... FOR UPDATE SKIP LOCKED polling,
-- no separate message broker. A row is deleted on success; 'failed' rows past max_attempts just
-- stop being claimable rather than needing a separate dead-letter status.

CREATE TABLE enrichment_job (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic_id    BIGINT      NOT NULL REFERENCES topic (id) ON DELETE CASCADE,
    status      TEXT        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'processing', 'failed')),
    attempts    INT         NOT NULL DEFAULT 0,
    run_after   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX enrichment_job_claim_idx ON enrichment_job (run_after) WHERE status IN ('pending', 'failed');
