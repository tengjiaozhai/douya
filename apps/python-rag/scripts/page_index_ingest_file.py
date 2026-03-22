#!/usr/bin/env python3
from __future__ import annotations

"""
文件上传入库脚本（CLI 入口）。

调用约定（支持两种方式）：
1) 文件路径直传（推荐调试）：`--file-path /path/to/file.pdf`
2) JSON 输入（兼容旧调用）：
   - stdin 管道传入 1 个 JSON 对象，或
   - `--payload-file` 从文件读取 JSON。
3) 解析文件内容（支持 PDF / DOCX / TXT 等），拆分为页面文本。
4) 合并“调用方 metadata”和“解析器 metadata”。
5) 调用 PageIndexRagService.ingest 执行入库，并输出 JSON 结果。
"""

import argparse
import base64
import json
import os
import sys
from pathlib import Path
from typing import Any


def _bootstrap_import_path() -> None:
    # 计算 apps/python-rag 目录，保证无论当前工作目录在哪都能导入 app.* 包。
    app_root = Path(__file__).resolve().parents[1]
    # 若尚未加入 sys.path，则插入到最前面，优先使用当前项目代码。
    if str(app_root) not in sys.path:
        sys.path.insert(0, str(app_root))


# 在导入 app.* 之前先执行路径引导。
_bootstrap_import_path()

from app.core.config import RagConfig  # noqa: E402
from app.core.reranker import build_reranker  # noqa: E402
from app.indexing.file_parser import parse_uploaded_document  # noqa: E402
from app.models.schemas import IngestRequest  # noqa: E402
from app.services.page_index_rag_service import PageIndexRagService  # noqa: E402
from app.storage.repository import JsonRepository  # noqa: E402


def _default_data_file() -> Path:
    # 优先读取环境变量 PAGE_INDEX_RAG_DATA_DIR；未设置时默认写到 data 目录。
    base = Path(os.getenv("PAGE_INDEX_RAG_DATA_DIR", "data"))
    # 固定使用 page_index_store.json 作为落盘文件名。
    return base / "page_index_store.json"


def _read_payload_from_stdin() -> dict[str, Any]:
    # 一次性读取标准输入全部内容。
    raw = sys.stdin.read()
    # 仅空白字符也视为无效输入，直接报错，避免后续 json 解析异常不明确。
    if not raw.strip():
        raise ValueError("stdin payload is empty")
    # 解析 JSON 字符串。
    payload = json.loads(raw)
    # 顶层必须是对象（dict），不接受数组或纯字符串。
    if not isinstance(payload, dict):
        raise ValueError("payload must be a json object")
    return payload


def _read_payload_from_file(payload_file: Path) -> dict[str, Any]:
    # 仅在调试/本地排障时使用：从 payload 文件读取 JSON，避免手动管道输入。
    if not payload_file.exists():
        raise ValueError(f"payload_file not found: {payload_file}")
    # 按 UTF-8 读取文本，便于保存中文 metadata。
    raw = payload_file.read_text(encoding="utf-8")
    # 空文件视为无效 payload。
    if not raw.strip():
        raise ValueError(f"payload_file is empty: {payload_file}")
    # 解析 JSON 字符串。
    payload = json.loads(raw)
    # 顶层必须是对象（dict），不接受数组或纯字符串。
    if not isinstance(payload, dict):
        raise ValueError("payload must be a json object")
    return payload


def _read_file_bytes_from_path(file_path: Path) -> tuple[str, bytes]:
    # 文件路径直传模式：只传路径即可，脚本内部读取字节并自动生成 file_name。
    if not file_path.exists():
        raise ValueError(f"file_path not found: {file_path}")
    if not file_path.is_file():
        raise ValueError(f"file_path is not a regular file: {file_path}")
    file_name = file_path.name
    file_bytes = file_path.read_bytes()
    if not file_bytes:
        raise ValueError("Uploaded file is empty.")
    return file_name, file_bytes


def _build_service(data_file: Path) -> PageIndexRagService:
    # 初始化 RAG 配置（用于切块、检索、重排等基础参数）。
    rag_cfg = RagConfig()
    # 构造 JSON 仓储，负责把文档索引快照写入/读取到 data_file。
    repo = JsonRepository(data_file)
    # 构造重排器；在检索阶段提升结果排序质量。
    reranker = build_reranker(rag_cfg)
    # 仅做 ingest（入库），不需要生成答案，因此 generator 显式置空。
    return PageIndexRagService(repo, rag_cfg, reranker=reranker, generator=None)


def _parse_user_metadata(raw: Any) -> dict[str, Any]:
    # metadata 缺失时返回空对象，保持下游字段稳定。
    if raw is None:
        return {}
    # 如果调用方本身传的是 JSON 对象，直接使用。
    if isinstance(raw, dict):
        return raw
    # 若传的是字符串，尝试按 JSON 字符串解析。
    if isinstance(raw, str):
        text = raw.strip()
        # 空字符串等价于未传。
        if not text:
            return {}
        try:
            parsed = json.loads(text)
            # 只接受解析后仍为对象的情况；否则降级保留原文，方便排查。
            return parsed if isinstance(parsed, dict) else {"raw_metadata": text}
        except json.JSONDecodeError:
            # 非法 JSON 字符串，保留原始文本，避免信息丢失。
            return {"raw_metadata": text}
    # 其他类型（如 list/int）统一忽略，返回空对象。
    return {}


def _merge_metadata(*, user_meta: dict[str, Any], parser_meta: dict[str, Any], source_type: str, filename: str) -> dict[str, Any]:
    # 以用户 metadata 为基础，确保“用户显式字段优先”。
    merged = dict(user_meta)
    # 追加上传来源类型（例如 pdf/docx/txt），便于后续过滤和诊断。
    merged["upload_source_type"] = source_type
    # 追加原始文件名，便于回溯。
    merged["filename"] = filename
    # 把解析器产生的结构化信息并入 metadata（如 OCR 摘要、图片资产等）。
    for k, v in parser_meta.items():
        # 用户未提供同名键：直接补充解析器值。
        if k not in merged:
            merged[k] = v
            continue
        # 同名且双方都是对象：做浅合并，用户字段覆盖解析器同名子键。
        if isinstance(merged[k], dict) and isinstance(v, dict):
            merged[k] = {**v, **merged[k]}
            continue
        # 同名但类型不兼容或非对象：保留用户值，同时把解析器值写到 parser_<key> 避免冲突。
        merged[f"parser_{k}"] = v
    return merged


def main() -> int:
    # 定义命令行参数，默认保留 json-stdin 兼容行为。
    parser = argparse.ArgumentParser(description="PageIndexRAG ingest-file script")
    parser.add_argument("--mode", choices=["json-stdin"], default="json-stdin")
    parser.add_argument(
        "--file-path",
        default="",
        help="可选。直接传入本地文件路径；传入后无需 stdin JSON。",
    )
    parser.add_argument(
        "--payload-file",
        default="",
        help="可选。调试时可直接从 JSON 文件读取 payload，避免使用 stdin 管道。",
    )
    parser.add_argument("--doc-id", default="", help="可选。文件路径直传模式下指定 doc_id。")
    parser.add_argument("--doc-name", default="", help="可选。文件路径直传模式下指定 doc_name。")
    parser.add_argument("--version", default="v1", help="可选。文件路径直传模式下指定版本，默认 v1。")
    parser.add_argument(
        "--metadata-json",
        default="",
        help="可选。文件路径直传模式下传 metadata（JSON 字符串）。",
    )
    parser.add_argument(
        "--data-file",
        default="",
        help="可选。文件路径直传模式下指定索引数据文件路径。",
    )
    # 触发参数解析；mode 仍保持与历史调用契约一致。
    args = parser.parse_args()

    try:
        # 优先走“文件路径直传模式”。
        file_path_raw = str(args.file_path).strip()
        if file_path_raw:
            file_path = Path(file_path_raw).expanduser().resolve()
            file_name, file_bytes = _read_file_bytes_from_path(file_path)
            data_file_raw = str(args.data_file).strip()
            data_file = Path(data_file_raw).expanduser().resolve() if data_file_raw else _default_data_file()
            user_meta = _parse_user_metadata(args.metadata_json)
            req_doc_id = str(args.doc_id).strip() or None
            req_doc_name = str(args.doc_name).strip() or file_name
            req_version = str(args.version).strip() or "v1"
        else:
            # 回退 JSON 输入模式（兼容旧调用）。
            payload_file = str(args.payload_file).strip()
            payload = (
                _read_payload_from_file(Path(payload_file).expanduser().resolve())
                if payload_file
                else _read_payload_from_stdin()
            )

            # 必填字段：文件名（用于推断类型）和 base64 文件内容。
            file_name = str(payload.get("file_name", "")).strip()
            file_base64 = str(payload.get("file_base64", "")).strip()
            if not file_name:
                raise ValueError("file_name is required")
            if not file_base64:
                raise ValueError("file_base64 is required")

            # 可选字段：data_file 指定自定义索引文件；不传则回落到默认 data/page_index_store.json。
            data_file_raw = payload.get("data_file")
            data_file = Path(str(data_file_raw)).expanduser().resolve() if data_file_raw else _default_data_file()

            # 解码文件内容并调用解析器拆页。
            file_bytes = base64.b64decode(file_base64)
            user_meta = _parse_user_metadata(payload.get("metadata"))
            req_doc_id = str(payload.get("doc_id", "")).strip() or None
            req_doc_name = str(payload.get("doc_name", "")).strip() or file_name
            req_version = str(payload.get("version", "v1")).strip() or "v1"

        # 无论哪种输入模式，统一走文件解析与入库流程。
        parsed = parse_uploaded_document(file_name, file_bytes)
        # 合并 metadata，形成最终入库元数据。
        merged_meta = _merge_metadata(
            user_meta=user_meta,
            parser_meta=parsed.metadata,
            source_type=parsed.source_type,
            filename=file_name,
        )

        # 组装 IngestRequest：
        # - doc_id 不传时由服务侧生成。
        # - doc_name 不传时默认使用 file_name。
        # - version 默认 v1。
        req = IngestRequest(
            doc_id=req_doc_id,
            doc_name=req_doc_name,
            pages=parsed.pages,
            metadata=merged_meta,
            version=req_version,
        )

        # 执行入库并输出标准 JSON，便于 Java/终端调用方解析。
        service = _build_service(data_file)
        resp = service.ingest(req)
        print(resp.model_dump_json())
        return 0
    except Exception as exc:  # pragma: no cover - integration path
        # 统一错误包结构：状态 + 错误码 + 错误信息。
        error = {"status": "FAILED", "code": "PYTHON_SCRIPT_INGEST_FILE_FAILED", "error": str(exc)}
        # ensure_ascii=False 保证中文错误信息不被转义。
        print(json.dumps(error, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    # 按命令行程序方式退出，返回 main() 的退出码。
    raise SystemExit(main())
