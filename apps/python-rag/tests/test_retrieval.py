from app.core.retrieval import keyword_match_score, rrf_fusion_multi


def test_rrf_fusion_multi_prefers_multi_route_hits() -> None:
    fused = rrf_fusion_multi(
        [
            {"a": 1, "b": 2},
            {"b": 1, "c": 2},
            {"b": 3, "a": 4},
        ],
        k=60,
    )
    assert fused["b"] > fused["a"]
    assert fused["b"] > fused["c"]


def test_keyword_match_score_prefers_high_idf_hit_terms() -> None:
    query_tokens = ["apple", "banana", "potassium"]
    idf = {"apple": 1.0, "banana": 1.0, "potassium": 3.0}

    doc_hit_high_idf = {"potassium": 0.4}
    doc_hit_low_idf = {"apple": 0.4}

    assert keyword_match_score(query_tokens, doc_hit_high_idf, idf) > keyword_match_score(
        query_tokens,
        doc_hit_low_idf,
        idf,
    )
