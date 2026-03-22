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
                metadata=_merge_metadata(
                    base=_parse_metadata(metadata, source_type=parsed.source_type, filename=file.filename),
                    parsed=parsed.metadata,
                ),
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
    if raw is None:
        return base
    raw = raw.strip()
    if not raw:
        return base
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        # Accept plain text metadata to keep upload flow frictionless.
        return {**base, "raw_metadata": raw}
    if isinstance(parsed, dict):
        parsed.update(base)
        return parsed
    return {**base, "raw_metadata": str(parsed)}


def _merge_metadata(*, base: dict, parsed: dict) -> dict:
    if not parsed:
        return base
    merged = dict(base)
    for k, v in parsed.items():
        if k not in merged:
            merged[k] = v
            continue
        if isinstance(merged[k], dict) and isinstance(v, dict):
            merged[k] = {**v, **merged[k]}
            continue
        merged[f"parser_{k}"] = v
    return merged
