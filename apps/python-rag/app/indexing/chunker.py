from __future__ import annotations

import re
from dataclasses import dataclass


_TOKEN_PATTERN = re.compile(r"[\u4e00-\u9fff]|[A-Za-z0-9_]+")


@dataclass(frozen=True)
class ChunkPart:
    chunk_no: int
    offset_start: int
    offset_end: int
    tokens: list[str]
    text: str


def normalize_text(text: str) -> str:
    compact = text.replace("\r\n", "\n").replace("\r", "\n")
    compact = re.sub(r"\n{3,}", "\n\n", compact)
    return compact.strip()


def tokenize(text: str) -> list[str]:
    return [t.lower() for t in _TOKEN_PATTERN.findall(text)]


def split_pages(content: str, max_chars: int = 1800) -> list[str]:
    normalized = normalize_text(content)
    if not normalized:
        return []
    if "\f" in normalized:
        pages = [normalize_text(p) for p in normalized.split("\f")]
        return [p for p in pages if p]

    paras = [p.strip() for p in normalized.split("\n\n") if p.strip()]
    pages: list[str] = []
    bucket = []
    current_len = 0
    for para in paras:
        if current_len + len(para) + 2 > max_chars and bucket:
            pages.append("\n\n".join(bucket))
            bucket = [para]
            current_len = len(para)
        else:
            bucket.append(para)
            current_len += len(para) + 2
    if bucket:
        pages.append("\n\n".join(bucket))
    return pages


def chunk_tokens(tokens: list[str], size: int, overlap: int) -> list[tuple[int, int, list[str]]]:
    if size <= 0:
        raise ValueError("size must be positive")
    if overlap >= size:
        raise ValueError("overlap must be smaller than size")
    ranges: list[tuple[int, int, list[str]]] = []
    i = 0
    chunk_no = 0
    while i < len(tokens):
        end = min(i + size, len(tokens))
        ranges.append((i, end, tokens[i:end]))
        if end == len(tokens):
            break
        i = end - overlap
        chunk_no += 1
    return ranges

