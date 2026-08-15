-- Schema for the node pipeline only: tracks, topic DAG, resources, concepts.
-- Tick and review tables (coverage, review, session) come in a later migration.

CREATE TABLE track (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    goal        TEXT        NOT NULL,
    level       TEXT        NOT NULL,
    source_jd   TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX track_user_idx ON track (user_id);


CREATE TABLE topic (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    track_id          BIGINT      NOT NULL REFERENCES track (id) ON DELETE CASCADE,
    label             TEXT        NOT NULL,
    normalized_label  TEXT        NOT NULL,
    is_core           BOOLEAN     NOT NULL DEFAULT true,
    is_root           BOOLEAN     NOT NULL DEFAULT false,

    expansion_status  TEXT        NOT NULL DEFAULT 'not_expanded'
        CHECK (expansion_status IN ('not_expanded', 'expanding', 'expanded', 'failed')),
    enrichment_status TEXT        NOT NULL DEFAULT 'pending'
        CHECK (enrichment_status IN ('pending', 'enriching', 'ready', 'failed')),

    searched_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The dedupe arbiter. Two concurrent expansions proposing the same child
    -- race here; the loser catches the violation and re-reads.
    CONSTRAINT topic_unique_in_track UNIQUE (track_id, normalized_label)
);

CREATE INDEX topic_track_idx ON topic (track_id);
CREATE INDEX topic_roots_idx ON topic (track_id) WHERE is_root;


-- The tree is a DAG: one topic can hang under several parents.
CREATE TABLE topic_edge (
    parent_topic_id BIGINT      NOT NULL REFERENCES topic (id) ON DELETE CASCADE,
    child_topic_id  BIGINT      NOT NULL REFERENCES topic (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (parent_topic_id, child_topic_id),
    CONSTRAINT topic_edge_no_self CHECK (parent_topic_id <> child_topic_id)
);

CREATE INDEX topic_edge_child_idx ON topic_edge (child_topic_id);


CREATE TABLE resource (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic_id     BIGINT      NOT NULL REFERENCES topic (id) ON DELETE CASCADE,

    type         TEXT        NOT NULL
        CHECK (type IN ('video', 'blog', 'docs', 'book', 'problem', 'other')),
    lane         TEXT        NOT NULL
        CHECK (lane IN ('read', 'watch', 'practice')),

    title        TEXT        NOT NULL,
    url          TEXT,
    citation     TEXT,   -- for offline resources: 'DDIA ch. 3, pp. 88-104'
    source_query TEXT,   -- what the search agent actually asked
    position     INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Stops "find more" from re-adding what's already there.
    -- NULLs compare distinct in Postgres, so book rows without URLs are unaffected.
    CONSTRAINT resource_unique_url UNIQUE (topic_id, url)
);

CREATE INDEX resource_topic_idx ON resource (topic_id);


CREATE TABLE concept (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic_id         BIGINT      NOT NULL REFERENCES topic (id) ON DELETE CASCADE,
    label            TEXT        NOT NULL,
    normalized_label TEXT        NOT NULL,
    origin           TEXT        NOT NULL DEFAULT 'upfront'
        CHECK (origin IN ('upfront', 'extracted')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT concept_unique_in_topic UNIQUE (topic_id, normalized_label)
);

CREATE INDEX concept_topic_idx ON concept (topic_id);


CREATE TABLE llm_call (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    call_name     TEXT        NOT NULL,   -- 'expand_node', 'generate_concepts', ...
    entity_type   TEXT,                   -- 'topic', 'resource', ...
    entity_id     BIGINT,
    model         TEXT        NOT NULL,
    prompt        TEXT        NOT NULL,
    response      TEXT,
    error         TEXT,
    latency_ms    INT,
    input_tokens  INT,
    output_tokens INT,
    cost_usd      NUMERIC(10, 6),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX llm_call_entity_idx ON llm_call (entity_type, entity_id);
CREATE INDEX llm_call_created_idx ON llm_call (created_at DESC);
