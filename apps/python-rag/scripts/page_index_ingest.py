#!/usr/bin/env python3
from __future__ import annotations

"""
纯 JSON 内容入库脚本（不走 file_base64 文件上传链路）。

调用约定（json-stdin 模式）：
1) 从标准输入读取 1 个 JSON 对象。
2) 基于 content/pages/metadata 组装 IngestRequest。
3) 调用 PageIndexRagService.ingest 并写入本地 JSON 索引文件。
"""

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any


def _bootstrap_import_path() -> None:
    # 定位 apps/python-rag 目录，保证 app.* 导入在任意 cwd 下都成立。
    app_root = Path(__file__).resolve().parents[1]
    # 若尚未在搜索路径中，插入到最前，优先使用当前仓库代码。
    if str(app_root) not in sys.path:
        sys.path.insert(0, str(app_root))


# 必须先执行路径引导，再导入 app.* 模块。
_bootstrap_import_path()

from app.core.config import RagConfig  # noqa: E402
from app.core.reranker import build_reranker  # noqa: E402
from app.models.schemas import IngestRequest  # noqa: E402
from app.services.page_index_rag_service import PageIndexRagService  # noqa: E402
from app.storage.repository import JsonRepository  # noqa: E402


def _default_data_file() -> Path:
    # 支持通过环境变量覆盖数据目录，方便多环境切换。
    base = Path(os.getenv("PAGE_INDEX_RAG_DATA_DIR", "data"))
    # 固定索引文件名，便于与其他脚本保持一致。
    return base / "page_index_store.json"


def _read_payload_from_stdin() -> dict[str, Any]:
    # 读取标准输入完整文本。
    raw = sys.stdin.read()
    # 空输入直接报错，避免进入后续空对象逻辑。
    if not raw.strip():
        raise ValueError("stdin payload is empty")
    # 解析 JSON。
    payload = json.loads(raw)
    # 顶层必须是对象，保证字段读取语义稳定。
    if not isinstance(payload, dict):
        raise ValueError("payload must be a json object")
    return payload


def _build_service(data_file: Path) -> PageIndexRagService:
    # 初始化全局 RAG 配置。
    rag_cfg = RagConfig()
    # 构建落盘仓储（JSON 文件存储）。
    repo = JsonRepository(data_file)
    # 构建重排器，提升检索结果排序质量。
    reranker = build_reranker(rag_cfg)
    # 仅做入库，不需要生成器（LLM 回答模块）。
    return PageIndexRagService(repo, rag_cfg, reranker=reranker, generator=None)


def _to_pages(value: Any) -> list[str] | None:
    # pages 未传时返回 None，让 schema 使用默认分支。
    if value is None:
        return None
    # 非列表类型不接受，避免脏数据写入。
    if not isinstance(value, list):
        return None
    # 把每个元素转字符串并去除首尾空白。
    pages = [str(v).strip() for v in value]
    # 过滤空字符串页面。
    pages = [p for p in pages if p]
    # 若过滤后为空，返回 None 表示“无有效页面”。
    return pages or None


def _to_metadata(value: Any) -> dict[str, Any]:
    # metadata 仅接受对象，保证下游字段结构一致。
    if isinstance(value, dict):
        return value
    # 非对象一律回退为空对象。
    return {}


def main() -> int:
    # 声明 CLI 入口参数；当前支持 json-stdin 单一模式。
    parser = argparse.ArgumentParser(description="PageIndexRAG ingest script")
    parser.add_argument("--mode", choices=["json-stdin"], default="json-stdin")
    # 执行参数解析（主要用于接口约束和 --help 文档）。
    _ = parser.parse_args()

    try:
        # 读取并校验请求体。
        payload = _read_payload_from_stdin()
        doc_name = str(payload.get("doc_name", "")).strip()
        # doc_name 是入库主键语义的一部分，必须提供。
        if not doc_name:
            raise ValueError("doc_name is required")

        # 支持外部显式指定数据文件路径；未指定则使用默认路径。
        data_file_raw = payload.get("data_file")
        data_file = Path(str(data_file_raw)).expanduser().resolve() if data_file_raw else _default_data_file()

        # 组装入库请求：content 与 pages 可二选一或同时提供，由服务层进一步校验。
        req = IngestRequest(
            doc_id=str(payload.get("doc_id", "")).strip() or None,
            doc_name=doc_name,
            content=(str(payload.get("content", "")).strip() or None),
            pages=_to_pages(payload.get("pages")),
            metadata=_to_metadata(payload.get("metadata")),
            version=str(payload.get("version", "v1")).strip() or "v1",
        )

        # 调用服务执行入库，并输出 JSON 结果。
        service = _build_service(data_file)
        resp = service.ingest(req)
        print(resp.model_dump_json())
        return 0
    except Exception as exc:  # pragma: no cover - integration path
        # 统一错误结构，便于上游（Java/脚本）稳定解析。
        error = {"status": "FAILED", "code": "PYTHON_SCRIPT_INGEST_FAILED", "error": str(exc)}
        # 关闭 ASCII 转义，直接输出中文错误内容。
        print(json.dumps(error, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    # 以命令行程序方式返回退出码。
    raise SystemExit(main())
