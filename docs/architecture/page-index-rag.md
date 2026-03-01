# PageIndexRAG Architecture

## Runtime (M1)

- API: FastAPI (`apps/python-rag/app/api`)
- Service: `PageIndexRagService`
- Storage: local JSON snapshot (`StorageSnapshot`)
- Retrieval: dense (hash embedding) + sparse (term overlap) + RRF fusion

## Flow

1. `ingest` receives `content/pages`.
2. Content is normalized and split to pages.
3. Each page is tokenized and chunked (`320/64`).
4. Chunk stores dense vector + sparse terms.
5. `query` performs dual retrieval and page aggregation.
6. Response returns answer text + citations.

## Next

- Replace local storage with Qdrant hybrid index.
- Replace heuristic rerank with cross-encoder reranker.
- Connect generation layer for final natural answer.
