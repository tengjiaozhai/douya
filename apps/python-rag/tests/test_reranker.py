from app.core.config import RagConfig
from app.core.reranker import LexicalReranker, RerankItem, build_reranker


def test_lexical_reranker_basic_order() -> None:
    reranker = LexicalReranker(RagConfig())
    items = [
        RerankItem(item_id="p1", text="苹果 富含 维生素", base_score=0.5),
        RerankItem(item_id="p2", text="香蕉 富含 钾", base_score=0.5),
    ]
    ranked = reranker.rerank("钾 在 哪", items)
    assert ranked[0][0] == "p2"


def test_build_reranker_fallback_to_lexical() -> None:
    cfg = RagConfig(rerank_provider="bge")
    reranker = build_reranker(cfg)
    assert reranker.name in {"lexical", "bge"}

