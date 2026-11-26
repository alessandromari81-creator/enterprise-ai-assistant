-- Schema condiviso da backend-laravel e backend-springboot.
-- Unica fonte di verità: nessuna migration Laravel / ddl-auto Hibernate deve toccare queste tabelle.

CREATE EXTENSION IF NOT EXISTS vector;

-- Dimensione embedding: 768 = nomic-embed-text (Ollama).
-- Con OpenAI usare text-embedding-3-small con parametro dimensions=768.
-- Cambiare modello/dimensione => ricreare document_chunks e reindicizzare.

CREATE TABLE documents (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),   -- PG18: uuidv7() se si vuole l'ordinamento temporale
    filename      VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0),
    content_hash  CHAR(64)     NOT NULL UNIQUE,                         -- SHA-256 esadecimale: evita duplicati
    page_count    INT,
    chunk_count   INT          NOT NULL DEFAULT 0,
    status        VARCHAR(12)  NOT NULL DEFAULT 'PROCESSING'
                  CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE document_chunks (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index  INT         NOT NULL,
    page_number  INT         NOT NULL,                                  -- base 1, usato per la citazione
    content      TEXT        NOT NULL,
    token_count  INT,
    embedding    VECTOR(768) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX document_chunks_document_idx ON document_chunks (document_id);
CREATE INDEX document_chunks_embedding_idx
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
-- Query di retrieval (distanza coseno; score = 1 - distanza):
--   SELECT id, document_id, page_number, content, 1 - (embedding <=> :q) AS score
--   FROM document_chunks ORDER BY embedding <=> :q LIMIT :k;

CREATE TABLE reimbursement_requests (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    number                 BIGINT        GENERATED ALWAYS AS IDENTITY UNIQUE,   -- numero progressivo leggibile (#1, #2...)
    amount                 NUMERIC(10,2) NOT NULL CHECK (amount > 0 AND amount <= 10000),
    currency               CHAR(3)       NOT NULL DEFAULT 'EUR',
    description            TEXT,
    status                 VARCHAR(10)   NOT NULL DEFAULT 'OPEN'
                           CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'EXECUTED')),
    requested_by           VARCHAR(100)  NOT NULL DEFAULT 'demo-user',
    source_conversation_id UUID,                                        -- chat da cui l'IA ha creato la richiesta
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX reimbursement_requests_created_idx ON reimbursement_requests (created_at DESC);
CREATE INDEX reimbursement_requests_status_idx  ON reimbursement_requests (status);

-- Memoria di conversazione (tabella di supporto, non esposta direttamente dall'API)
CREATE TABLE chat_messages (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL,
    role            VARCHAR(10) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content         TEXT        NOT NULL,
    sources         JSONB,
    actions         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX chat_messages_conversation_idx ON chat_messages (conversation_id, created_at);
