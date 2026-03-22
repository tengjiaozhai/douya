from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from app.core.config import RagConfig
from app.core.retrieval import build_idf, sparse_similarity, sparse_terms


# Rerank 输入项：把“候选页面”包装成统一结构，便于不同重排器复用。
@dataclass(frozen=True)
class RerankItem:
    # 候选项唯一标识（这里通常是 page_id）。
    item_id: str
    # 候选项文本（页面全文）。
    text: str
    # 初始分数（来自前面的召回阶段）。
    base_score: float


# Protocol = “接口约定”：任何重排器只要实现这个方法，就能被系统使用。
class PageReranker(Protocol):
    # 重排器名字，用于 debug 日志展示。
    name: str

    def rerank(self, query: str, items: list[RerankItem]) -> list[tuple[str, float]]:
        # 返回 [(item_id, score)]，按分数从高到低排序。
        ...


# 词法重排器：不依赖外部模型，稳定、可离线运行。
class LexicalReranker:
    name = "lexical"

    def __init__(self, cfg: RagConfig) -> None:
        # 读取配置，例如“词法分占比 alpha”。
        self.cfg = cfg

    def rerank(self, query: str, items: list[RerankItem]) -> list[tuple[str, float]]:
        # 把查询转成稀疏词权重。
        q_terms = sparse_terms(query)
        # 提前计算候选页面的词典集合，用于构建 IDF。
        page_sparse_docs = [sparse_terms(i.text) for i in items]
        # 基于当前候选集合算 IDF（小范围动态 IDF）。
        idf = build_idf(page_sparse_docs)
        # 保存每个候选的重排后分数。
        scored: list[tuple[str, float]] = []
        # 逐个候选页面打分。
        for item in items:
            # lexical 分：查询词与页面词的重叠质量。
            lexical = sparse_similarity(q_terms, sparse_terms(item.text), idf)
            # 最终分 = 初始分和词法分加权混合。
            # 例如 alpha=0.2：
            # score = base*0.8 + lexical*0.2
            # 含义：保留召回阶段主导地位，同时让词法匹配纠偏。
            score = item.base_score * (1.0 - self.cfg.rerank_lexical_alpha) + lexical * self.cfg.rerank_lexical_alpha
            scored.append((item.item_id, score))
        # 按重排后分数降序返回。
        return sorted(scored, key=lambda x: x[1], reverse=True)


# BGE 重排器：使用外部模型做语义重排，效果更强但依赖更多。
class BgeReranker:
    name = "bge"

    def __init__(self, cfg: RagConfig) -> None:
        # 保存配置（模型名、batch size、权重等）。
        self.cfg = cfg
        try:
            # 延迟导入，只有启用 bge 时才需要该依赖。
            from FlagEmbedding import FlagReranker
        except Exception as exc:  # pragma: no cover - depends on optional package
            # 缺依赖时给出明确安装提示。
            raise RuntimeError(
                "BGE reranker requires `FlagEmbedding` package. "
                "Install it in conda env: pip install FlagEmbedding"
            ) from exc
        # 初始化模型。use_fp16=False 更稳，适配更多 CPU/GPU 环境。
        self._model = FlagReranker(cfg.rerank_model_name, use_fp16=False)

    def rerank(self, query: str, items: list[RerankItem]) -> list[tuple[str, float]]:
        # 没候选就直接返回空列表。
        if not items:
            return []
        # 组装模型输入对：[query, candidate_text]。
        pairs = [[query, i.text] for i in items]
        # 批量推理，返回每个候选的语义相关分。
        raw_scores = self._model.compute_score(pairs, batch_size=self.cfg.rerank_batch_size)
        # 某些实现在单条输入时可能返回标量，这里统一转 list。
        if not isinstance(raw_scores, list):
            raw_scores = [float(raw_scores)]
        # 保存融合后的最终分。
        scored: list[tuple[str, float]] = []
        # 将模型分与基础分融合。
        for item, rerank_score in zip(items, raw_scores):
            # base_weight 越大，越信任召回阶段分数。
            # 例子：base_weight=0.7
            # final = base*0.7 + bge*0.3
            score = item.base_score * self.cfg.rerank_base_weight + float(rerank_score) * (
                1.0 - self.cfg.rerank_base_weight
            )
            scored.append((item.item_id, score))
        # 按最终分降序输出。
        return sorted(scored, key=lambda x: x[1], reverse=True)


def build_reranker(cfg: RagConfig) -> PageReranker:
    """工厂函数：根据配置选择重排器，并内置降级策略。"""
    # provider 字段做小写和去空白，容错调用方输入。
    provider = cfg.rerank_provider.lower().strip()
    # 显式要求 bge 时，优先尝试 bge。
    if provider == "bge":
        try:
            return BgeReranker(cfg)
        except Exception:
            # bge 初始化失败（缺包/模型问题）时自动降级 lexical，
            # 这样查询不会因依赖问题完全不可用。
            return LexicalReranker(cfg)
    # 默认使用 lexical（最稳、依赖最少）。
    return LexicalReranker(cfg)
