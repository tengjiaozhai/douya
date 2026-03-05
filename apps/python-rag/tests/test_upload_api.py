from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import build_router
from app.core.config import RagConfig
from app.services.page_index_rag_service import PageIndexRagService
from app.storage.repository import JsonRepository


def test_upload_txt_file_ingest(tmp_path: Path) -> None:
    repo = JsonRepository(tmp_path / "store.json")
    service = PageIndexRagService(repo, RagConfig())
    app = FastAPI()
    app.include_router(build_router(service))
    client = TestClient(app)

    files = {"file": ("demo.txt", "第一页内容\n\n第二段。\f第二页内容".encode("utf-8"), "text/plain")}
    data = {"version": "v1", "metadata": '{"source":"pytest"}'}
    resp = client.post("/api/rag/page-index/ingest/file", files=files, data=data)

    assert resp.status_code == 200
    body = resp.json()
    assert body["page_count"] == 2
    assert body["chunk_count"] >= 2

    status = client.get("/api/rag/page-index/status")
    assert status.status_code == 200
    assert status.json()["docs"] == 1
