from __future__ import annotations

import base64
import json
import logging
import os
from dataclasses import dataclass, field
from io import BytesIO
from pathlib import Path
from typing import Any
from urllib.error import URLError
from urllib.request import Request, urlopen

from app.indexing.chunker import normalize_text, split_pages

logger = logging.getLogger(__name__)


class UnsupportedFileTypeError(ValueError):
    pass


@dataclass(frozen=True)
class ParsedDocument:
    pages: list[str]
    source_type: str
    metadata: dict[str, Any] = field(default_factory=dict)


_TEXT_EXTS = {
    ".txt",
    ".md",
    ".markdown",
    ".csv",
    ".tsv",
    ".json",
    ".jsonl",
    ".yaml",
    ".yml",
    ".xml",
    ".html",
    ".htm",
    ".log",
    ".ini",
    ".conf",
}


def parse_uploaded_document(filename: str, data: bytes) -> ParsedDocument:
    if not data:
        raise ValueError("Uploaded file is empty.")
    ext = Path(filename).suffix.lower()

    if ext in _TEXT_EXTS or not ext:
        content = _decode_text(data)
        pages = split_pages(content)
        return ParsedDocument(pages=pages, source_type="text", metadata={})

    if ext == ".pdf":
        return _parse_pdf_document(filename, data)

    if ext == ".docx":
        content = _parse_docx_text(data)
        pages = split_pages(content)
        return ParsedDocument(pages=pages, source_type="docx", metadata={})

    raise UnsupportedFileTypeError(
        f"Unsupported file type '{ext}'. Supported: text/*, .pdf, .docx"
    )


def _decode_text(data: bytes) -> str:
    for enc in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("latin-1")


def _parse_pdf_pages(data: bytes) -> list[str]:
    try:
        from pypdf import PdfReader
    except Exception as exc:  # pragma: no cover - import error depends on environment
        raise RuntimeError("pypdf is required for PDF upload. Please install pypdf.") from exc

    reader = PdfReader(BytesIO(data))
    pages: list[str] = []
    for page in reader.pages:
        text = normalize_text(page.extract_text() or "")
        if text:
            pages.append(text)
    if not pages:
        raise ValueError("PDF contains no extractable text.")
    return pages


def _parse_pdf_document(filename: str, data: bytes) -> ParsedDocument:
    pages_from_pdf = _extract_pdf_pages_allow_empty(data)
    doc_stem = _sanitize_file_stem(filename)
    result_pages: list[str] = []
    page_image_assets: list[dict[str, Any]] = []

    ocr_pages = 0
    native_pages = 0
    ocr_error: str | None = None
    min_chars = _env_int("PAGE_INDEX_RAG_OCR_MIN_TEXT_CHARS", 20)

    for page_idx, native_text in enumerate(pages_from_pdf, start=1):
        if native_text:
            native_pages += 1
        page_png = None
        ocr_text = ""

        # 扫描件通常原生文本极少，走 OCR 回退。
        if len(native_text) < min_chars:
            page_png, render_error = _render_pdf_page_to_png(data, page_idx - 1)
            if render_error:
                ocr_error = render_error
            if page_png:
                ocr_text, ocr_runtime_error = _extract_ocr_text_from_png(page_png)
                if ocr_runtime_error:
                    ocr_error = ocr_runtime_error
                if ocr_text:
                    ocr_pages += 1

        oss_url = None
        if page_png and _env_bool("PAGE_INDEX_RAG_OSS_UPLOAD_ENABLED", True):
            asset_name = f"{doc_stem}_p{page_idx}.png"
            oss_url = _upload_image_to_java_oss(
                image_bytes=page_png,
                file_name=asset_name,
                document_name=doc_stem,
            )
            if oss_url:
                page_image_assets.append(
                    {"page_no": page_idx, "file_name": asset_name, "oss_url": oss_url}
                )

        merged = _build_hybrid_page_text(native_text=native_text, ocr_text=ocr_text, oss_url=oss_url)
        result_pages.append(merged)

    if not any(p.strip() for p in result_pages):
        if ocr_error:
            raise ValueError(
                "PDF contains no extractable text and OCR failed. "
                f"reason={ocr_error}"
            )
        raise ValueError("PDF contains no extractable text.")

    metadata: dict[str, Any] = {
        "ocr_summary": {
            "total_pages": len(result_pages),
            "native_text_pages": native_pages,
            "ocr_pages": ocr_pages,
            "ocr_enabled": True,
        },
        "page_image_assets": page_image_assets,
    }

    source_type = "pdf+ocr" if ocr_pages > 0 else "pdf"
    return ParsedDocument(pages=result_pages, source_type=source_type, metadata=metadata)


def _extract_pdf_pages_allow_empty(data: bytes) -> list[str]:
    try:
        from pypdf import PdfReader
    except Exception as exc:  # pragma: no cover - import error depends on environment
        raise RuntimeError("pypdf is required for PDF upload. Please install pypdf.") from exc

    reader = PdfReader(BytesIO(data))
    pages: list[str] = []
    for page in reader.pages:
        pages.append(normalize_text(page.extract_text() or ""))
    return pages


def _render_pdf_page_to_png(data: bytes, page_index: int) -> tuple[bytes | None, str | None]:
    try:
        import fitz  # type: ignore
    except Exception as exc:  # pragma: no cover - import error depends on environment
        return None, f"pymupdf_unavailable: {exc}"

    scale = float(os.getenv("PAGE_INDEX_RAG_OCR_RENDER_SCALE", "2.0"))
    try:
        with fitz.open(stream=data, filetype="pdf") as pdf_doc:
            if page_index < 0 or page_index >= len(pdf_doc):
                return None, f"page_index_out_of_range: {page_index}"
            page = pdf_doc[page_index]
            matrix = fitz.Matrix(scale, scale)
            pix = page.get_pixmap(matrix=matrix, alpha=False)
            return pix.tobytes("png"), None
    except Exception as exc:  # pragma: no cover - runtime depends on file quality
        return None, f"render_failed: {exc}"


_OCR_ENGINE = None


def _extract_ocr_text_from_png(image_bytes: bytes) -> tuple[str, str | None]:
    global _OCR_ENGINE
    try:
        from rapidocr_onnxruntime import RapidOCR  # type: ignore
    except Exception as exc:  # pragma: no cover - import error depends on environment
        return "", f"rapidocr_unavailable: {exc}"

    try:
        if _OCR_ENGINE is None:
            _OCR_ENGINE = RapidOCR()
        result, _ = _OCR_ENGINE(image_bytes)
    except Exception as exc:  # pragma: no cover - runtime depends on OCR runtime
        return "", f"ocr_runtime_failed: {exc}"

    text = _flatten_ocr_result(result)
    return normalize_text(text), None


def _flatten_ocr_result(result: Any) -> str:
    if not result:
        return ""
    lines: list[str] = []
    for item in result:
        if isinstance(item, (list, tuple)):
            if len(item) >= 2 and isinstance(item[1], str):
                lines.append(item[1])
                continue
            if len(item) >= 2 and isinstance(item[1], (list, tuple)) and item[1]:
                if isinstance(item[1][0], str):
                    lines.append(item[1][0])
                    continue
        if isinstance(item, dict):
            text = item.get("text")
            if isinstance(text, str):
                lines.append(text)
    return "\n".join(s for s in lines if s and s.strip())


def _build_hybrid_page_text(*, native_text: str, ocr_text: str, oss_url: str | None) -> str:
    parts: list[str] = []
    if native_text:
        parts.append(native_text)
    if ocr_text:
        if native_text and ocr_text in native_text:
            pass
        else:
            parts.append(f"[OCR文本]\n{ocr_text}")
    if oss_url:
        parts.append(f"[图片资产]: ossUrl={oss_url}")
    merged = "\n\n".join(parts).strip()
    return merged


def _upload_image_to_java_oss(*, image_bytes: bytes, file_name: str, document_name: str) -> str | None:
    upload_url = os.getenv(
        "PAGE_INDEX_RAG_OSS_UPLOAD_URL",
        "http://127.0.0.1:8787/api/douya/page-index-rag/assets/upload-image",
    ).strip()
    if not upload_url:
        return None

    payload = {
        "file_name": file_name,
        "document_name": document_name,
        "content_base64": base64.b64encode(image_bytes).decode("ascii"),
    }
    timeout_seconds = _env_int("PAGE_INDEX_RAG_OSS_UPLOAD_TIMEOUT_SECONDS", 12)

    try:
        req = Request(
            url=upload_url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(req, timeout=timeout_seconds) as resp:  # noqa: S310
            body = resp.read().decode("utf-8")
        data = json.loads(body)
        if str(data.get("status", "")).upper() == "SUCCESS":
            oss_url = str(data.get("oss_url", "")).strip()
            return oss_url or None
        logger.warning("java_oss_upload_failed status=%s body=%s", data.get("status"), body[:300])
    except (URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
        logger.warning("java_oss_upload_request_failed file=%s error=%s", file_name, exc)
    return None


def _sanitize_file_stem(filename: str) -> str:
    stem = Path(filename).stem or "uploaded_document"
    return "".join(ch if (ch.isalnum() or ch in {"_", "-", "."}) else "_" for ch in stem)


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _parse_docx_text(data: bytes) -> str:
    try:
        from docx import Document
    except Exception as exc:  # pragma: no cover - import error depends on environment
        raise RuntimeError("python-docx is required for DOCX upload. Please install python-docx.") from exc

    doc = Document(BytesIO(data))
    lines: list[str] = []
    for p in doc.paragraphs:
        text = normalize_text(p.text)
        if text:
            lines.append(text)
    if not lines:
        raise ValueError("DOCX contains no extractable text.")
    return "\n\n".join(lines)
