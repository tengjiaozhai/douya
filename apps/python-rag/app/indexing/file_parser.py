from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

from app.indexing.chunker import normalize_text, split_pages


class UnsupportedFileTypeError(ValueError):
    pass


@dataclass(frozen=True)
class ParsedDocument:
    pages: list[str]
    source_type: str


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
        return ParsedDocument(pages=pages, source_type="text")

    if ext == ".pdf":
        pages = _parse_pdf_pages(data)
        return ParsedDocument(pages=pages, source_type="pdf")

    if ext == ".docx":
        content = _parse_docx_text(data)
        pages = split_pages(content)
        return ParsedDocument(pages=pages, source_type="docx")

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
