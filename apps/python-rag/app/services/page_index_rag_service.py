from __future__ import annotations

import logging
from collections import defaultdict
from typing import Iterable
from uuid import uuid4

from app.core.config import RagConfig
from app.core.generator import AnswerGenerator, EvidenceSnippet
from app.core.reranker import PageReranker, RerankItem
from app.core.retrieval import (
    build_idf,
    cosine_similarity,
    hash_embedding,
    rrf_fusion,
    sparse_similarity,
    sparse_terms,
)
from app.indexing.chunker import chunk_tokens, normalize_text, split_pages, tokenize
from app.models.schemas import (
    Citation,
    IngestRequest,
    IngestResponse,
    QueryDebug,
    QueryRequest,
    QueryResponse,
    StatusResponse,
    StorageSnapshot,
    StoredChunk,
    StoredDocument,
    StoredPage,
    utc_now_iso,
)
from app.storage.repository import JsonRepository
from app.storage.qdrant_store import QdrantChunkStore

logger = logging.getLogger(__name__)


def _chunk_text_from_tokens(tokens: list[str]) -> str:
    # Keep retrieval text deterministic and compact.
    return " ".join(tokens)


class PageIndexRagService:
    def __init__(
        self,
        repo: JsonRepository,
        rag_config: RagConfig,
        qdrant_store: QdrantChunkStore | None = None,
        reranker: PageReranker | None = None,
        generator: AnswerGenerator | None = None,
    ) -> None:
        self.repo = repo
        self.cfg = rag_config
        self.qdrant_store = qdrant_store
        self.reranker = reranker
        self.generator = generator

    def ingest(self, req: IngestRequest) -> IngestResponse:
        pages = self._extract_pages(req)
        if not pages:
            raise ValueError("No valid content/pages provided for ingestion.")

        snapshot = self.repo.load()
        doc_id = req.doc_id or str(uuid4())
        doc = snapshot.documents.get(doc_id)
        now = utc_now_iso()

        if doc is None:
            doc = StoredDocument(
                doc_id=doc_id,
                doc_name=req.doc_name,
                version=req.version,
                metadata=req.metadata,
                created_at=now,
                updated_at=now,
            )
        else:
            doc.doc_name = req.doc_name
            doc.version = req.version
            doc.metadata = req.metadata
            doc.updated_at = now
            self._remove_doc_data(snapshot, doc_id)

        snapshot.documents[doc_id] = doc
        all_new_chunks: list[StoredChunk] = []

        if self.qdrant_store is not None:
            self.qdrant_store.delete_doc_chunks(doc_id)

        for idx, page_text in enumerate(pages, start=1):
            page_id = f"{doc_id}:p{idx}"
            page_tokens = tokenize(page_text)
            page = StoredPage(
                page_id=page_id,
                doc_id=doc_id,
                page_no=idx,
                page_text=page_text,
                page_summary=page_text[: self.cfg.summary_chars],
                keywords=_top_keywords(page_tokens, n=8),
            )
            snapshot.pages[page_id] = page

            ranges = chunk_tokens(page_tokens, self.cfg.child_chunk_size, self.cfg.child_overlap)
            for chunk_no, (start, end, part_tokens) in enumerate(ranges, start=1):
                text = _chunk_text_from_tokens(part_tokens)
                chunk_id = f"{page_id}:c{chunk_no}"
                chunk = StoredChunk(
                    chunk_id=chunk_id,
                    page_id=page_id,
                    doc_id=doc_id,
                    page_no=idx,
                    chunk_no=chunk_no,
                    offset_start=start,
                    offset_end=end,
                    chunk_text=text,
                    token_count=len(part_tokens),
                    dense_vector=hash_embedding(text, self.cfg.vector_dim),
                    sparse_terms=sparse_terms(text),
                )
                snapshot.chunks[chunk_id] = chunk
                all_new_chunks.append(chunk)

        snapshot.updated_at = now
        self.repo.save(snapshot)
        if self.qdrant_store is not None:
            self.qdrant_store.upsert_chunks(all_new_chunks)
        return IngestResponse(
            doc_id=doc_id,
            version=req.version,
            page_count=len(pages),
            chunk_count=len(all_new_chunks),
            updated_at=now,
        )

    def query(self, req: QueryRequest) -> QueryResponse:
        snapshot = self.repo.load()
        logger.info("query_start query=%s top_k=%s", req.query[:60], req.top_k)
        if not snapshot.chunks:
            logger.info("query_empty_store")
            return QueryResponse(
                answer="知识库为空，请先调用 ingest 接口导入文档。",
                citations=[],
                debug=QueryDebug(
                    retrieved_chunks=0,
                    candidate_pages=0,
                    rerank_pages=0,
                    reranker=getattr(self.reranker, "name", None),
                    generator=getattr(self.generator, "name", None),
                    retrieval_source=None,
                )
                if req.with_debug
                else None,
            )

        query = normalize_text(req.query)
        q_vector = hash_embedding(query, self.cfg.vector_dim)
        q_terms = sparse_terms(query)
        fused, retrieval_source = self._fused_chunk_scores(snapshot, q_vector, q_terms)

        scored_chunks = sorted(fused.items(), key=lambda x: x[1], reverse=True)
        selected_chunks = [
            (snapshot.chunks[chunk_id], score)
            for chunk_id, score in scored_chunks
            if score >= self.cfg.min_score_threshold
        ]
        if not selected_chunks:
            selected_chunks = [
                (snapshot.chunks[chunk_id], score)
                for chunk_id, score in scored_chunks[: max(10, req.top_k * 2)]
            ]
            logger.info("query_threshold_fallback selected=%s", len(selected_chunks))

        page_scores: dict[str, float] = defaultdict(float)
        page_to_chunks: dict[str, list[tuple[StoredChunk, float]]] = defaultdict(list)
        for chunk, score in selected_chunks:
            page_scores[chunk.page_id] += score
            page_to_chunks[chunk.page_id].append((chunk, score))

        candidate_pages = sorted(page_scores.items(), key=lambda x: x[1], reverse=True)[: self.cfg.page_pool_size]
        expanded_pages = self._expand_neighbors(candidate_pages, snapshot.pages)
        reranked_pages = self._rerank_pages(query, expanded_pages, snapshot)
        top_pages = reranked_pages[: min(req.top_k, self.cfg.max_context_pages)]

        citations: list[Citation] = []
        evidence_snippets: list[EvidenceSnippet] = []
        for page_id, _ in top_pages:
            chunks_scored = sorted(page_to_chunks.get(page_id, []), key=lambda x: x[1], reverse=True)
            for chunk, _ in chunks_scored[:2]:
                citations.append(Citation(doc_id=chunk.doc_id, page_no=chunk.page_no, chunk_id=chunk.chunk_id))
                evidence_snippets.append(EvidenceSnippet(page_no=chunk.page_no, text=chunk.chunk_text))

        answer = self._compose_answer(query, evidence_snippets)
        logger.info(
            "query_done retrieved=%s candidate_pages=%s rerank_pages=%s source=%s reranker=%s generator=%s",
            len(selected_chunks),
            len(candidate_pages),
            len(top_pages),
            retrieval_source,
            getattr(self.reranker, "name", None),
            getattr(self.generator, "name", None),
        )
        debug = None
        if req.with_debug:
            debug = QueryDebug(
                retrieved_chunks=len(selected_chunks),
                candidate_pages=len(candidate_pages),
                rerank_pages=len(top_pages),
                reranker=getattr(self.reranker, "name", None),
                generator=getattr(self.generator, "name", None),
                retrieval_source=retrieval_source,
            )
        return QueryResponse(answer=answer, citations=citations[: req.top_k], debug=debug)

    def status(self) -> StatusResponse:
        snapshot = self.repo.load()
        return StatusResponse(
            docs=len(snapshot.documents),
            pages=len(snapshot.pages),
            chunks=len(snapshot.chunks),
            updated_at=snapshot.updated_at,
        )

    def _remove_doc_data(self, snapshot: StorageSnapshot, doc_id: str) -> None:
        remove_pages = [pid for pid, p in snapshot.pages.items() if p.doc_id == doc_id]
        for pid in remove_pages:
            snapshot.pages.pop(pid, None)
        remove_chunks = [cid for cid, c in snapshot.chunks.items() if c.doc_id == doc_id]
        for cid in remove_chunks:
            snapshot.chunks.pop(cid, None)

    def _extract_pages(self, req: IngestRequest) -> list[str]:
        if req.pages:
            return [normalize_text(p) for p in req.pages if normalize_text(p)]
        if req.content:
            return split_pages(req.content)
        return []

    def _expand_neighbors(
        self,
        candidate_pages: list[tuple[str, float]],
        pages: dict[str, StoredPage],
    ) -> list[tuple[str, float]]:
        merged: dict[str, float] = {pid: score for pid, score in candidate_pages}
        page_index = {(p.doc_id, p.page_no): p.page_id for p in pages.values()}
        for pid, score in candidate_pages:
            page = pages.get(pid)
            if page is None:
                continue
            for delta in range(1, self.cfg.neighbor_window + 1):
                for next_no in (page.page_no - delta, page.page_no + delta):
                    nb_pid = page_index.get((page.doc_id, next_no))
                    if nb_pid and nb_pid not in merged:
                        merged[nb_pid] = score * 0.75
        return sorted(merged.items(), key=lambda x: x[1], reverse=True)

    def _rerank_pages(
        self,
        query: str,
        expanded_pages: list[tuple[str, float]],
        snapshot: StorageSnapshot,
    ) -> list[tuple[str, float]]:
        if self.reranker is None:
            return expanded_pages[: self.cfg.rerank_top_k]
        items: list[RerankItem] = []
        for page_id, base_score in expanded_pages:
            page = snapshot.pages.get(page_id)
            if page is None:
                continue
            items.append(RerankItem(item_id=page_id, text=page.page_text, base_score=base_score))
        return self.reranker.rerank(query, items)[: self.cfg.rerank_top_k]

    def _compose_answer(self, query: str, snippets: Iterable[EvidenceSnippet]) -> str:
        selected = [s for s in snippets if s.text]
        if self.generator is None:
            return f"未配置生成器。问题：{query}。"
        return self.generator.generate(query, selected)

    def _fused_chunk_scores(
        self,
        snapshot: StorageSnapshot,
        q_vector: list[float],
        q_terms: dict[str, float],
    ) -> tuple[dict[str, float], str]:
        if self.qdrant_store is not None:
            try:
                scores = self.qdrant_store.hybrid_search(
                    q_dense=q_vector,
                    q_sparse_terms=q_terms,
                    dense_top_k=self.cfg.dense_top_k,
                    sparse_top_k=self.cfg.sparse_top_k,
                )
                return scores, "qdrant_hybrid"
            except Exception:
                # Fallback to local retrieval if qdrant query fails.
                logger.warning("retrieval_fallback_to_local reason=qdrant_error")
                pass

        chunks = list(snapshot.chunks.values())
        all_sparse_docs = [c.sparse_terms for c in chunks]
        idf = build_idf(all_sparse_docs)
        dense_sorted = sorted(
            ((c.chunk_id, cosine_similarity(q_vector, c.dense_vector)) for c in chunks),
            key=lambda x: x[1],
            reverse=True,
        )[: self.cfg.dense_top_k]
        sparse_sorted = sorted(
            ((c.chunk_id, sparse_similarity(q_terms, c.sparse_terms, idf)) for c in chunks),
            key=lambda x: x[1],
            reverse=True,
        )[: self.cfg.sparse_top_k]
        dense_ranks = {chunk_id: i + 1 for i, (chunk_id, _) in enumerate(dense_sorted)}
        sparse_ranks = {chunk_id: i + 1 for i, (chunk_id, _) in enumerate(sparse_sorted)}
        return rrf_fusion(dense_ranks, sparse_ranks, self.cfg.rrf_k), "local_rrf"


def _top_keywords(tokens: list[str], n: int) -> list[str]:
    freq: dict[str, int] = {}
    for t in tokens:
        freq[t] = freq.get(t, 0) + 1
    return [k for k, _ in sorted(freq.items(), key=lambda x: x[1], reverse=True)[:n]]
