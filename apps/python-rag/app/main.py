from __future__ import annotations

import logging

from fastapi import FastAPI

from app.api.routes import build_router
from app.core.config import AppSettings
from app.core.generator import build_generator
from app.core.reranker import build_reranker
from app.services.page_index_rag_service import PageIndexRagService
from app.storage.repository import JsonRepository
from app.storage.qdrant_store import QdrantChunkStore

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

settings = AppSettings()
repo = JsonRepository(settings.data_file)
qdrant_store = None
if settings.qdrant.enabled:
    try:
        qdrant_store = QdrantChunkStore(settings.qdrant, settings.rag)
    except Exception:
        qdrant_store = None
reranker = build_reranker(settings.rag)
generator = build_generator(settings.generation)
service = PageIndexRagService(
    repo,
    settings.rag,
    qdrant_store=qdrant_store,
    reranker=reranker,
    generator=generator,
)

app = FastAPI(title=settings.app_name, version="0.1.0")
app.include_router(build_router(service))


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
