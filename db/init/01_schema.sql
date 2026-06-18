-- pgvector schema for RAG document chunks.
-- Loaded by pgvector/pgvector:pg16 via /docker-entrypoint-initdb.d on first boot.

CREATE EXTENSION IF NOT EXISTS vector;

-- Embedding dimension defaults to 4096 (Qwen3-VL-Embedding-8B).
-- If you switch embedding models, change vector(4096) here and VECTORSTORE_DIMENSION.
CREATE TABLE IF NOT EXISTS document_chunk (
    chunk_id          TEXT PRIMARY KEY,
    document_id       TEXT NOT NULL,
    tenant_id         TEXT NOT NULL,
    filename          TEXT,
    content           TEXT,
    content_hash      TEXT,
    chunk_index       INTEGER NOT NULL DEFAULT 0,
    owner_id          TEXT,
    department_id     TEXT,
    visibility        TEXT NOT NULL DEFAULT 'DEPARTMENT',
    allowed_user_ids  TEXT[] NOT NULL DEFAULT '{}',
    allowed_role_ids  TEXT[] NOT NULL DEFAULT '{}',
    status            TEXT NOT NULL DEFAULT 'ACTIVE',
    document_status   TEXT NOT NULL DEFAULT 'READY',
    version           INTEGER NOT NULL DEFAULT 1,
    permission_level  INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ,
    embedding         vector(4096)
);

-- ANN index for cosine distance (<=>). Tune m / ef_construction for recall vs build time.
CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding_hnsw
    ON document_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Tenant + document filtering and version invalidation.
CREATE INDEX IF NOT EXISTS idx_document_chunk_tenant_doc
    ON document_chunk (tenant_id, document_id);

-- CUSTOM visibility ACL matching (allowed_user_ids / allowed_role_ids overlap).
CREATE INDEX IF NOT EXISTS idx_document_chunk_allowed_users
    ON document_chunk USING gin (allowed_user_ids);
CREATE INDEX IF NOT EXISTS idx_document_chunk_allowed_roles
    ON document_chunk USING gin (allowed_role_ids);

-- Usage records for cost auditing / billing (scope B). Written by JdbcUsageRecordRepository.
CREATE TABLE IF NOT EXISTS usage_record (
    id                 BIGSERIAL PRIMARY KEY,
    request_id         TEXT,
    tenant_id          TEXT,
    user_id            TEXT,
    interface_name     TEXT,
    model              TEXT,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    total_tokens       INTEGER,
    cached_tokens      INTEGER,
    estimated_cost     NUMERIC(18, 6),
    latency_ms         BIGINT,
    success            BOOLEAN,
    created_at         TIMESTAMPTZ DEFAULT now()
);

-- Summary query filters by tenant + time window.
CREATE INDEX IF NOT EXISTS idx_usage_record_tenant_time
    ON usage_record (tenant_id, created_at);
