#!/usr/bin/env python3
from __future__ import annotations

import argparse
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
from app.services.page_index_rag_service import PageIndexRagService  # noqa: E402
from app.storage.repository import JsonRepository  # noqa: E402


def _default_data_file() -> Path:
    base = Path(os.getenv("PAGE_INDEX_RAG_DATA_DIR", "data"))
    return base / "page_index_store.json"


def _read_payload_from_stdin() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise ValueError("payload must be a json object")
    return payload


def _build_service(data_file: Path) -> PageIndexRagService:
    rag_cfg = RagConfig()
    repo = JsonRepository(data_file)
    reranker = build_reranker(rag_cfg)
    return PageIndexRagService(repo, rag_cfg, reranker=reranker, generator=None)


def main() -> int:
    parser = argparse.ArgumentParser(description="PageIndexRAG status script")
    parser.add_argument("--mode", choices=["json-stdin"], default="json-stdin")
    _ = parser.parse_args()

    try:
        payload = _read_payload_from_stdin()
        data_file_raw = payload.get("data_file")
        data_file = Path(str(data_file_raw)).expanduser().resolve() if data_file_raw else _default_data_file()
        service = _build_service(data_file)
        resp = service.status()
        print(resp.model_dump_json())
        return 0
    except Exception as exc:  # pragma: no cover - integration path
        error = {"status": "FAILED", "code": "PYTHON_SCRIPT_STATUS_FAILED", "error": str(exc)}
        print(json.dumps(error, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

