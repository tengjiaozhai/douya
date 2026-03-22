from pathlib import Path

from app.core.config import RagConfig
from app.models.schemas import IngestRequest, QueryRequest
from app.services.page_index_rag_service import PageIndexRagService
from app.storage.repository import JsonRepository


def test_ingest_and_query(tmp_path: Path) -> None:
    repo = JsonRepository(tmp_path / "store.json")
    service = PageIndexRagService(repo, RagConfig())

    ingest_resp = service.ingest(
        IngestRequest(
            doc_name="test-doc",
            pages=["苹果 富含 维生素 C", "香蕉 富含 钾"],
        )
    )
    assert ingest_resp.page_count == 2
    assert ingest_resp.chunk_count >= 2

    query_resp = service.query(QueryRequest(query="钾 在 哪一页", top_k=3))
    assert query_resp.citations
    assert any(c.page_no == 2 for c in query_resp.citations)


def test_query_debug_contains_keyword_route(tmp_path: Path) -> None:
    repo = JsonRepository(tmp_path / "store.json")
    service = PageIndexRagService(repo, RagConfig())

    service.ingest(
        IngestRequest(
            doc_name="keyword-doc",
            pages=[
                "hyperflux_omega reactor calibration manual",
                "banana potassium nutrition notes",
            ],
        )
    )

    query_resp = service.query(QueryRequest(query="hyperflux_omega", top_k=2, with_debug=True))
    assert query_resp.debug is not None
    assert query_resp.debug.retrieval_source is not None
    assert "keyword" in query_resp.debug.retrieval_source
    assert query_resp.debug.retrieval_route_hits is not None
    assert query_resp.debug.retrieval_route_hits["keyword"] > 0
    assert any(c.page_no == 1 for c in query_resp.citations)
