# Reindex Runbook (Draft)

## Scope

Runbook for pageIndexRAG incremental/full reindex.

## M1

- Storage: local JSON file (`apps/python-rag/data/page_index_store.json`)
- Reindex strategy: re-call `POST /api/rag/page-index/ingest` with same `doc_id` to overwrite existing pages/chunks.

## M2 (planned)

- Add async job endpoint.
- Add document versioned reindex and rollback.

