from __future__ import annotations

import hashlib
from collections.abc import Iterable

from qdrant_client import QdrantClient, models

from app.core.config import QdrantConfig, RagConfig
from app.models.schemas import StoredChunk


def _term_to_index(term: str) -> int:
    digest = hashlib.md5(term.encode("utf-8")).hexdigest()
    return int(digest[:8], 16)


def _to_sparse_vector(terms: dict[str, float]) -> models.SparseVector:
    if not terms:
        return models.SparseVector(indices=[], values=[])
    pairs = sorted((_term_to_index(k), float(v)) for k, v in terms.items())
    return models.SparseVector(
        indices=[p[0] for p in pairs],
        values=[p[1] for p in pairs],
    )


class QdrantChunkStore:
    def __init__(self, cfg: QdrantConfig, rag_cfg: RagConfig) -> None:
        self.cfg = cfg
        self.rag_cfg = rag_cfg
        self.client = QdrantClient(
            url=cfg.url,
            api_key=cfg.api_key,
            timeout=cfg.timeout,
        )
        self._ensure_collection()

    def _ensure_collection(self) -> None:
        exists = self.client.collection_exists(self.cfg.collection)
        if exists:
            return
        self.client.create_collection(
            collection_name=self.cfg.collection,
            vectors_config={
                "dense": models.VectorParams(size=self.rag_cfg.vector_dim, distance=models.Distance.COSINE),
            },
            sparse_vectors_config={
                "sparse": models.SparseVectorParams(),
            },
        )

    def upsert_chunks(self, chunks: Iterable[StoredChunk]) -> None:
        points: list[models.PointStruct] = []
        for chunk in chunks:
            point = models.PointStruct(
                id=chunk.chunk_id,
                vector={
                    "dense": chunk.dense_vector,
                    "sparse": _to_sparse_vector(chunk.sparse_terms),
                },
                payload={
                    "chunk_id": chunk.chunk_id,
                    "doc_id": chunk.doc_id,
                    "page_id": chunk.page_id,
                    "page_no": chunk.page_no,
                    "chunk_no": chunk.chunk_no,
                },
            )
            points.append(point)
        if points:
            self.client.upsert(collection_name=self.cfg.collection, points=points, wait=True)

    def delete_doc_chunks(self, doc_id: str) -> None:
        self.client.delete(
            collection_name=self.cfg.collection,
            points_selector=models.FilterSelector(
                filter=models.Filter(
                    must=[
                        models.FieldCondition(
                            key="doc_id",
                            match=models.MatchValue(value=doc_id),
                        )
                    ]
                )
            ),
            wait=True,
        )

    def hybrid_search(
        self,
        q_dense: list[float],
        q_sparse_terms: dict[str, float],
        dense_top_k: int,
        sparse_top_k: int,
    ) -> dict[str, float]:
        sparse = _to_sparse_vector(q_sparse_terms)
        response = self.client.query_points(
            collection_name=self.cfg.collection,
            prefetch=[
                models.Prefetch(query=q_dense, using="dense", limit=dense_top_k),
                models.Prefetch(query=sparse, using="sparse", limit=sparse_top_k),
            ],
            query=models.FusionQuery(fusion=models.Fusion.RRF),
            with_payload=False,
            with_vectors=False,
            limit=max(dense_top_k, sparse_top_k),
        )
        scores: dict[str, float] = {}
        for p in response.points:
            scores[str(p.id)] = float(p.score or 0.0)
        return scores

    def count(self) -> int:
        return self.client.count(collection_name=self.cfg.collection).count

