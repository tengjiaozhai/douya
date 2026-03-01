from __future__ import annotations

from fastapi import APIRouter, HTTPException

from app.models.schemas import IngestRequest, IngestResponse, QueryRequest, QueryResponse, StatusResponse
from app.services.page_index_rag_service import PageIndexRagService


def build_router(service: PageIndexRagService) -> APIRouter:
    router = APIRouter(prefix="/api/rag/page-index", tags=["page-index-rag"])

    @router.post("/ingest", response_model=IngestResponse)
    async def ingest(request: IngestRequest) -> IngestResponse:
        try:
            return service.ingest(request)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/query", response_model=QueryResponse)
    async def query(request: QueryRequest) -> QueryResponse:
        return service.query(request)

    @router.get("/status", response_model=StatusResponse)
    async def status() -> StatusResponse:
        return service.status()

    return router

