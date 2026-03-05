from __future__ import annotations

import json

from fastapi import APIRouter, HTTPException
from fastapi.params import File, Form
from fastapi.datastructures import UploadFile

from app.indexing.file_parser import UnsupportedFileTypeError, parse_uploaded_document
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
    
    @router.post("/ingest/file", response_model=IngestResponse)
    async def ingest_file(
        file: UploadFile = File(...),
        doc_id: str | None = Form(default=None),
        doc_name: str | None = Form(default=None),
        version: str = Form(default="v1"),
        metadata: str | None = Form(default=None),
    ) -> IngestResponse:
        try:
            body = await file.read()
            parsed = parse_uploaded_document(file.filename or "", body)
            req = IngestRequest(
                doc_id=doc_id,
                doc_name=doc_name or file.filename or "uploaded-document",
                pages=parsed.pages,
                metadata=_parse_metadata(metadata, source_type=parsed.source_type, filename=file.filename),
                version=version,
            )
            return service.ingest(req)
        except (ValueError, UnsupportedFileTypeError, RuntimeError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/query", response_model=QueryResponse)
    async def query(request: QueryRequest) -> QueryResponse:
        return service.query(request)

    @router.get("/status", response_model=StatusResponse)
    async def status() -> StatusResponse:
        return service.status()

    return router


def _parse_metadata(raw: str | None, *, source_type: str, filename: str | None) -> dict:
    base = {"upload_source_type": source_type}
    if filename:
        base["filename"] = filename
    if not raw:
        return base
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("metadata must be valid JSON object string") from exc
    if not isinstance(parsed, dict):
        raise ValueError("metadata must be a JSON object")
    parsed.update(base)
    return parsed

