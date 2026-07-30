-- pgvector schema for RAG document chunks.
-- Loaded by pgvector/pgvector:pg16 via /docker-entrypoint-initdb.d on first boot.

CREATE EXTENSION IF NOT EXISTS vector;

-- Embedding dimension defaults to 1024 (DashScope text-embedding-v4).
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
    -- 独立父块外键（Parent-Document）：null = 无父块。命中子块后按它回填 document_parent。
    parent_id         TEXT,
    -- 多模态：TEXT（默认，零回归）或 IMAGE。IMAGE chunk 的向量来自 VL 图像 embedding，与文本同空间。
    modality          TEXT NOT NULL DEFAULT 'TEXT',
    -- IMAGE chunk 的图片引用（对象存储 objectKey / 图片服务 ref）；TEXT chunk 为 null。
    image_ref         TEXT,
    created_at        TIMESTAMPTZ,
    embedding         vector(1024)
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

-- 独立父块存储（Parent-Document）：子块只存 parent_id，父块正文放这里。
-- 检索命中子块后按 parent_id 回填父块给 LLM；权限列与 document_chunk 对齐，便于同样的 SQL 过滤。
CREATE TABLE IF NOT EXISTS document_parent (
    parent_id         TEXT PRIMARY KEY,
    document_id       TEXT NOT NULL,
    tenant_id         TEXT NOT NULL,
    content           TEXT,
    owner_id          TEXT,
    department_id     TEXT,
    visibility        TEXT NOT NULL DEFAULT 'DEPARTMENT',
    allowed_user_ids  TEXT[] NOT NULL DEFAULT '{}',
    allowed_role_ids  TEXT[] NOT NULL DEFAULT '{}',
    version           INTEGER NOT NULL DEFAULT 1,
    permission_level  INTEGER NOT NULL DEFAULT 0
);

-- Parent lookup by document + version invalidation (mirrors document_chunk cleanup).
CREATE INDEX IF NOT EXISTS idx_document_parent_doc_version
    ON document_parent (document_id, version);

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
