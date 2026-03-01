from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, Field


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class IngestRequest(BaseModel):
    doc_id: str | None = None
    doc_name: str = Field(..., min_length=1, max_length=256)
    content: str | None = Field(default=None)
    pages: list[str] | None = Field(default=None)
    metadata: dict[str, Any] = Field(default_factory=dict)
    version: str = "v1"


class IngestResponse(BaseModel):
    doc_id: str
    version: str
    page_count: int
    chunk_count: int
    updated_at: str


class QueryRequest(BaseModel):
    query: str = Field(..., min_length=1)
    top_k: int = 10
    with_debug: bool = True


class Citation(BaseModel):
    doc_id: str
    page_no: int
    chunk_id: str


class QueryDebug(BaseModel):
    retrieved_chunks: int
    candidate_pages: int
    rerank_pages: int
    reranker: str | None = None
    generator: str | None = None
    retrieval_source: str | None = None


class QueryResponse(BaseModel):
    answer: str
    citations: list[Citation]
    debug: QueryDebug | None = None


class StatusResponse(BaseModel):
    docs: int
    pages: int
    chunks: int
    updated_at: str | None = None


class StoredDocument(BaseModel):
    doc_id: str = Field(default_factory=lambda: str(uuid4()))
    doc_name: str
    version: str
    metadata: dict[str, Any] = Field(default_factory=dict)
    created_at: str = Field(default_factory=utc_now_iso)
    updated_at: str = Field(default_factory=utc_now_iso)


class StoredPage(BaseModel):
    page_id: str
    doc_id: str
    page_no: int
    page_text: str
    page_summary: str
    keywords: list[str]


class StoredChunk(BaseModel):
    chunk_id: str
    page_id: str
    doc_id: str
    page_no: int
    chunk_no: int
    offset_start: int
    offset_end: int
    chunk_text: str
    token_count: int
    dense_vector: list[float]
    sparse_terms: dict[str, float]


class StorageSnapshot(BaseModel):
    documents: dict[str, StoredDocument] = Field(default_factory=dict)
    pages: dict[str, StoredPage] = Field(default_factory=dict)
    chunks: dict[str, StoredChunk] = Field(default_factory=dict)
    updated_at: str | None = None
