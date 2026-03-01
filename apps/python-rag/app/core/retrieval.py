from __future__ import annotations

import hashlib
import math
from collections import Counter

from app.indexing.chunker import tokenize


def l2_normalize(vec: list[float]) -> list[float]:
    norm = math.sqrt(sum(v * v for v in vec))
    if norm == 0:
        return vec
    return [v / norm for v in vec]


def hash_embedding(text: str, dim: int = 384) -> list[float]:
    tokens = tokenize(text)
    vec = [0.0] * dim
    if not tokens:
        return vec
    for t in tokens:
        digest = hashlib.sha256(t.encode("utf-8")).hexdigest()
        idx = int(digest[:8], 16) % dim
        sign = 1.0 if int(digest[8:10], 16) % 2 == 0 else -1.0
        vec[idx] += sign
    return l2_normalize(vec)


def cosine_similarity(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    return sum(x * y for x, y in zip(a, b))


def sparse_terms(text: str) -> dict[str, float]:
    terms = tokenize(text)
    if not terms:
        return {}
    tf = Counter(terms)
    total = float(len(terms))
    return {k: v / total for k, v in tf.items()}


def sparse_similarity(
    query_terms: dict[str, float],
    doc_terms: dict[str, float],
    idf: dict[str, float],
) -> float:
    score = 0.0
    for term, q_tf in query_terms.items():
        d_tf = doc_terms.get(term)
        if d_tf is None:
            continue
        score += q_tf * d_tf * idf.get(term, 1.0)
    return score


def build_idf(all_docs_terms: list[dict[str, float]]) -> dict[str, float]:
    n = max(len(all_docs_terms), 1)
    df: Counter[str] = Counter()
    for terms in all_docs_terms:
        for key in terms.keys():
            df[key] += 1
    return {k: math.log(1 + (n - v + 0.5) / (v + 0.5)) for k, v in df.items()}


def rrf_fusion(ranks_dense: dict[str, int], ranks_sparse: dict[str, int], k: int) -> dict[str, float]:
    keys = set(ranks_dense) | set(ranks_sparse)
    fused: dict[str, float] = {}
    for key in keys:
        dense_rank = ranks_dense.get(key)
        sparse_rank = ranks_sparse.get(key)
        dense_score = 1.0 / (k + dense_rank) if dense_rank is not None else 0.0
        sparse_score = 1.0 / (k + sparse_rank) if sparse_rank is not None else 0.0
        fused[key] = dense_score + sparse_score
    return fused

