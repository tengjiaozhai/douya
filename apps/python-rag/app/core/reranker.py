from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from app.core.config import RagConfig
from app.core.retrieval import build_idf, sparse_similarity, sparse_terms


@dataclass(frozen=True)
class RerankItem:
    item_id: str
    text: str
    base_score: float


class PageReranker(Protocol):
    name: str

    def rerank(self, query: str, items: list[RerankItem]) -> list[tuple[str, float]]:
        ...


class LexicalReranker:
    name = "lexical"

    def __init__(self, cfg: RagConfig) -> None:
        self.cfg = cfg

    def rerank(self, query: str, items: list[RerankItem]) -> list[tuple[str, float]]:
        q_terms = sparse_terms(query)
        page_sparse_docs = [sparse_terms(i.text) for i in items]
        idf = build_idf(page_sparse_docs)
        scored: list[tuple[str, float]] = []
        for item in items:
            lexical = sparse_similarity(q_terms, sparse_terms(item.text), idf)
            score = item.base_score * (1.0 - self.cfg.rerank_lexical_alpha) + lexical * self.cfg.rerank_lexical_alpha
            scored.append((item.item_id, score))
        return sorted(scored, key=lambda x: x[1], reverse=True)


class BgeReranker:
    name = "bge"

    def __init__(self, cfg: RagConfig) -> None:
        self.cfg = cfg
        try:
            from FlagEmbedding import FlagReranker
        except Exception as exc:  # pragma: no cover - depends on optional package
            raise RuntimeError(
                "BGE reranker requires `FlagEmbedding` package. "
                "Install it in conda env: pip install FlagEmbedding"
            ) from exc
        self._model = FlagReranker(cfg.rerank_model_name, use_fp16=False)

    def rerank(self, query: str, items: list[RerankItem]) -> list[tuple[str, float]]:
        if not items:
            return []
        pairs = [[query, i.text] for i in items]
        raw_scores = self._model.compute_score(pairs, batch_size=self.cfg.rerank_batch_size)
        if not isinstance(raw_scores, list):
            raw_scores = [float(raw_scores)]
        scored: list[tuple[str, float]] = []
        for item, rerank_score in zip(items, raw_scores):
            score = item.base_score * self.cfg.rerank_base_weight + float(rerank_score) * (
                1.0 - self.cfg.rerank_base_weight
            )
            scored.append((item.item_id, score))
        return sorted(scored, key=lambda x: x[1], reverse=True)


def build_reranker(cfg: RagConfig) -> PageReranker:
    provider = cfg.rerank_provider.lower().strip()
    if provider == "bge":
        try:
            return BgeReranker(cfg)
        except Exception:
            return LexicalReranker(cfg)
    return LexicalReranker(cfg)

