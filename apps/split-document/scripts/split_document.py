#!/usr/bin/env python3
"""Split-document tool integrated for douya.

Modes:
1) JSON stdin mode for Java integration:
   python split_document.py --mode json-stdin
   stdin payload:
   {
     "pages": [{"pageNumber": 1, "text": "..."}],
     "childChunkSize": 300,
     "childChunkOverlap": 50,
     "parentContextSize": 500
   }

   stdout payload:
   {
     "chunks": [
       {"text": "...", "startPage": 1, "endPage": 2, "parentText": "..."}
     ]
   }

2) Legacy file mode:
   python split_document.py -i <pdf> -o <output-dir>
"""

from __future__ import annotations

import argparse
import bisect
import datetime as dt
import json
import os
import re
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any


def _normalize_text(text: str) -> str:
    if not text:
        return ""
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)
    text = re.sub(r"[^\S\r\n]{2,}", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _recursive_split(text: str, limit: int, overlap: int) -> list[str]:
    separators = ["\n\n", "\n", "。", "！", "？", ". ", " "]
    chunks: list[str] = []

    def recurse(segment: str, sep_idx: int) -> None:
        if len(segment) <= limit:
            chunks.append(segment)
            return

        if sep_idx >= len(separators):
            step = max(1, limit - overlap)
            i = 0
            while i < len(segment):
                end = min(i + limit, len(segment))
                chunks.append(segment[i:end])
                if end >= len(segment):
                    break
                i += step
            return

        sep = separators[sep_idx]
        if sep:
            parts = re.split(f"(?<={re.escape(sep)})", segment)
        else:
            parts = [segment]

        current = ""
        for part in parts:
            if len(current) + len(part) > limit:
                if current:
                    chunks.append(current)
                    tail = current[max(0, len(current) - overlap) :]
                    current = tail
                if len(part) > limit:
                    recurse(part, sep_idx + 1)
                    continue
            current += part

        if len(current) > overlap:
            chunks.append(current)

    recurse(text, 0)
    return [c for c in chunks if c and c.strip()]


@dataclass
class _Page:
    page_number: int
    text: str


def _json_stdin_mode() -> int:
    raw = sys.stdin.read()
    payload = json.loads(raw) if raw.strip() else {}

    pages_raw = payload.get("pages", [])
    pages = [_Page(int(p.get("pageNumber", 0)), _normalize_text(str(p.get("text", "")))) for p in pages_raw]
    pages = [p for p in pages if p.page_number > 0 and p.text]

    size = int(payload.get("childChunkSize", 300))
    overlap = int(payload.get("childChunkOverlap", 50))
    parent_window = int(payload.get("parentContextSize", 500))

    if not pages:
        print(json.dumps({"chunks": []}, ensure_ascii=False))
        return 0

    full_text_parts: list[str] = []
    offsets: list[int] = []
    page_map: list[int] = []
    current = 0
    for p in pages:
        offsets.append(current)
        page_map.append(p.page_number)
        part = p.text + "\n\n"
        full_text_parts.append(part)
        current += len(part)
    full_text = "".join(full_text_parts)

    children = _recursive_split(full_text, size, overlap)

    def to_page(offset: int) -> int:
        i = bisect.bisect_right(offsets, max(0, offset)) - 1
        if i < 0:
            return page_map[0]
        return page_map[i]

    chunks: list[dict[str, Any]] = []
    search_from = 0
    for child in children:
        start = full_text.find(child, search_from)
        if start < 0:
            start = search_from
        end = min(len(full_text), start + len(child))
        search_from = max(0, start + len(child) // 2)

        start_page = to_page(start)
        end_page = to_page(max(0, end - 1))

        parent_start = max(0, start - parent_window)
        parent_end = min(len(full_text), end + parent_window)
        parent_text = _normalize_text(full_text[parent_start:parent_end])

        chunks.append(
            {
                "text": _normalize_text(child),
                "startPage": int(start_page),
                "endPage": int(end_page),
                "parentText": parent_text,
            }
        )

    print(json.dumps({"chunks": chunks}, ensure_ascii=False))
    return 0


# Legacy mode (upstream behavior style)
def _save_jsonl(chunks: list[dict[str, Any]], output_path: Path) -> None:
    with output_path.open("w", encoding="utf-8") as f:
        for item in chunks:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")


def _legacy_file_mode(input_file: str, output_dir: str) -> int:
    # This mode keeps backward compatibility for manual script runs.
    # It expects plain text file or pre-extracted text as lightweight fallback.
    path = Path(input_file)
    if not path.exists():
        print(f"Error: input not found: {path}", file=sys.stderr)
        return 2

    text = path.read_text(encoding="utf-8", errors="ignore")
    text = _normalize_text(text)

    chunks = _recursive_split(text, 800, 100)
    source_id = str(uuid.uuid4())
    now = dt.datetime.now().isoformat()

    records = []
    for i, c in enumerate(chunks):
        records.append(
            {
                "content": c,
                "metadata": {
                    "source_id": source_id,
                    "source_file": path.name,
                    "chunk_index": i,
                    "processor": "SliceMaster-compat",
                    "timestamp": now,
                },
            }
        )

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{path.stem}_split.jsonl"
    _save_jsonl(records, out_path)
    print(str(out_path))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="SliceMaster splitter for douya")
    parser.add_argument("--mode", choices=["json-stdin", "legacy-file"], default="legacy-file")
    parser.add_argument("-i", "--input", default="")
    parser.add_argument("-o", "--output", default="alreadySplit")
    args = parser.parse_args()

    if args.mode == "json-stdin":
        return _json_stdin_mode()
    return _legacy_file_mode(args.input, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
