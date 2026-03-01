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

