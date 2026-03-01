# RAG Playbook

## Scope

Use this file when the task is about ingestion, chunking, retrieval quality, fallback strategy, citation, or image/media retention in Douya.

## Source Alignment

1. Align with:
- `README.md` (Agentic RAG and parent-child strategy).
- `docs/architecture/page-index-rag.md` (Python M1 runtime flow).
- `apps/python-rag/README.md` (API shape and hybrid retrieval path).
- `libs/rag-schema/README.md` (shared schema constraints).
2. Preserve current baseline:
- Child for recall precision.
- Parent for generation context.
- Hybrid retrieval when useful (dense + sparse + fusion).

## Execution Workflow

1. Confirm change type:
- `ingest`: parse/split/metadata.
- `retrieve`: recall, rerank, fusion, threshold.
- `response`: citations, source trace, image URL retention.
2. Verify contract compatibility:
- Do not break Java/Python shared DTO expectations.
- Keep status/debug fields backward-compatible if possible.
3. Implement smallest viable patch:
- Prefer tuning splitter parameters or rerank logic first.
- Avoid rewriting the full pipeline unless current architecture blocks the target.
4. Add observability:
- Log query, top-k candidates, score distribution, final selected chunks.
- Log fallback trigger reason when retrieval misses.
5. Validate:
- One short query case (recall sensitive).
- One long query case (noise sensitive).
- One miss case (fallback path).

## Parent-Child Rules

1. Keep child chunks semantically focused and sufficiently overlapping.
2. Preserve parent text reference in metadata for context expansion.
3. Expand to parent only at generation/answer assembly phase.
4. Deduplicate expanded parents before final prompt assembly.

## Hybrid Retrieval Rules

1. Run dense retrieval for semantic recall.
2. Run sparse/term retrieval for literal anchors and short terms.
3. Fuse with RRF or equivalent stable fusion logic.
4. Re-rank only top candidates to control latency.

## Media/Citation Preservation

1. Never drop media pointers from retrieval metadata (for example OSS URLs).
2. Keep source fields sufficient for downstream formatter rendering.
3. If output format is changed, verify formatter still recognizes image/source markers.

## Regression Checklist

1. Hit quality does not decrease on known good queries.
2. Miss behavior is explicit, not hallucinated.
3. Latency stays within acceptable envelope for the endpoint.
4. Debug mode still exposes enough ranking context for diagnosis.
