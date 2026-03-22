#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from pathlib import Path
from typing import Any


def _bootstrap_import_path() -> None:
    app_root = Path(__file__).resolve().parents[1]
    if str(app_root) not in sys.path:
        sys.path.insert(0, str(app_root))


_bootstrap_import_path()

from app.core.config import RagConfig  # noqa: E402
from app.core.reranker import build_reranker  # noqa: E402
from app.indexing.file_parser import parse_uploaded_document  # noqa: E402
from app.models.schemas import IngestRequest  # noqa: E402
from app.services.page_index_rag_service import PageIndexRagService  # noqa: E402
from app.storage.repository import JsonRepository  # noqa: E402


def _default_data_file() -> Path:
    base = Path(os.getenv("PAGE_INDEX_RAG_DATA_DIR", "data"))
    return base / "page_index_store.json"


def _read_payload_from_stdin() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        raise ValueError("stdin payload is empty")
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise ValueError("payload must be a json object")
    return payload


def _build_service(data_file: Path) -> PageIndexRagService:
    rag_cfg = RagConfig()
    repo = JsonRepository(data_file)
    reranker = build_reranker(rag_cfg)
    return PageIndexRagService(repo, rag_cfg, reranker=reranker, generator=None)


def _parse_user_metadata(raw: Any) -> dict[str, Any]:
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        text = raw.strip()
        if not text:
            return {}
        try:
            parsed = json.loads(text)
            return parsed if isinstance(parsed, dict) else {"raw_metadata": text}
        except json.JSONDecodeError:
            return {"raw_metadata": text}
    return {}


def _merge_metadata(*, user_meta: dict[str, Any], parser_meta: dict[str, Any], source_type: str, filename: str) -> dict[str, Any]:
    merged = dict(user_meta)
    merged["upload_source_type"] = source_type
    merged["filename"] = filename
    for k, v in parser_meta.items():
        if k not in merged:
            merged[k] = v
            continue
        if isinstance(merged[k], dict) and isinstance(v, dict):
            merged[k] = {**v, **merged[k]}
            continue
        merged[f"parser_{k}"] = v
    return merged


def main() -> int:
    parser = argparse.ArgumentParser(description="PageIndexRAG ingest-file script")
    parser.add_argument("--mode", choices=["json-stdin"], default="json-stdin")
    _ = parser.parse_args()

    try:
        payload = _read_payload_from_stdin()
        file_name = str(payload.get("file_name", "")).strip()
        file_base64 = str(payload.get("file_base64", "")).strip()
        if not file_name:
            raise ValueError("file_name is required")
        if not file_base64:
            raise ValueError("file_base64 is required")

        data_file_raw = payload.get("data_file")
        data_file = Path(str(data_file_raw)).expanduser().resolve() if data_file_raw else _default_data_file()

        file_bytes = base64.b64decode(file_base64)
        parsed = parse_uploaded_document(file_name, file_bytes)

        user_meta = _parse_user_metadata(payload.get("metadata"))
        merged_meta = _merge_metadata(
            user_meta=user_meta,
            parser_meta=parsed.metadata,
            source_type=parsed.source_type,
            filename=file_name,
        )

        req = IngestRequest(
            doc_id=str(payload.get("doc_id", "")).strip() or None,
            doc_name=str(payload.get("doc_name", "")).strip() or file_name,
            pages=parsed.pages,
            metadata=merged_meta,
            version=str(payload.get("version", "v1")).strip() or "v1",
        )
        service = _build_service(data_file)
        resp = service.ingest(req)
        print(resp.model_dump_json())
        return 0
    except Exception as exc:  # pragma: no cover - integration path
        error = {"status": "FAILED", "code": "PYTHON_SCRIPT_INGEST_FILE_FAILED", "error": str(exc)}
        print(json.dumps(error, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

