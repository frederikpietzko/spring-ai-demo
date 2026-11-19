-- Hand-written pgvector DDL (ADR-0006). This directory is invisible to the Exposed migration generator, which replays
-- db/migration on plain PostgreSQL and cannot create the vector extension. Names stay unqualified so the scratch-schema
-- replay in the tests works; the vector type resolves from public.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id        text PRIMARY KEY,  -- readable and stable, e.g. policy-wording/exclusions.md#0-0
    content   text,
    metadata  json,              -- corpus, source, title (plus the splitter's bookkeeping)
    embedding vector(768)        -- nomic-embed-text
);

CREATE INDEX vector_store_embedding_idx ON vector_store USING hnsw (embedding vector_cosine_ops);
