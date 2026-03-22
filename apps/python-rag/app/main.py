from __future__ import annotations

import logging
import os

from fastapi import FastAPI, HTTPException

from app.api.routes import build_router
from app.core.config import AppSettings
from app.core.generator import build_generator
from app.core.reranker import build_reranker
from app.services.page_index_rag_service import PageIndexRagService
from app.storage.repository import JsonRepository

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

settings = AppSettings()
repo = JsonRepository(settings.data_file)
reranker = build_reranker(settings.rag)
generator = build_generator(settings.generation)
service = PageIndexRagService(
    repo,
    settings.rag,
    reranker=reranker,
    generator=generator,
)

app = FastAPI(title=settings.app_name, version="0.1.0")

enable_http_service = os.getenv("PAGE_INDEX_RAG_ENABLE_HTTP_SERVICE", "false").strip().lower() in {
    "1",
    "true",
    "yes",
    "y",
}
if enable_http_service:
    app.include_router(build_router(service))

@app.get("/health")
async def health() -> dict[str, str]:
    if enable_http_service:
        return {"status": "ok"}
    return {
        "status": "deprecated",
        "mode": "script-only",
        "message": "python-rag HTTP API 已废弃，默认禁用。请改用 scripts/page_index_*.py。",
    }


@app.api_route("/api/rag/page-index/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"])
async def deprecated_page_index_http(path: str) -> None:
    if enable_http_service:
        raise HTTPException(status_code=404, detail=f"unknown path: {path}")
    raise HTTPException(
        status_code=410,
        detail="python-rag HTTP API 已废弃，默认禁用。请改用 scripts/page_index_*.py。",
    )
