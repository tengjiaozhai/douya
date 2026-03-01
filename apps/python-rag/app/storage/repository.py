from __future__ import annotations

import json
from pathlib import Path
from threading import RLock

from app.models.schemas import StorageSnapshot


class JsonRepository:
    def __init__(self, file_path: Path) -> None:
        self.file_path = file_path
        self._lock = RLock()
        self.file_path.parent.mkdir(parents=True, exist_ok=True)

    def load(self) -> StorageSnapshot:
        with self._lock:
            if not self.file_path.exists():
                return StorageSnapshot()
            data = json.loads(self.file_path.read_text(encoding="utf-8"))
            return StorageSnapshot.model_validate(data)

    def save(self, snapshot: StorageSnapshot) -> None:
        with self._lock:
            self.file_path.write_text(
                snapshot.model_dump_json(indent=2),
                encoding="utf-8",
            )

