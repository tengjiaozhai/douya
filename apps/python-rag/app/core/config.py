from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class RagConfig:
    child_chunk_size: int = 320
    child_overlap: int = 64
    dense_top_k: int = 60
    sparse_top_k: int = 60
    rrf_k: int = 60
    page_pool_size: int = 20
    neighbor_window: int = 1
    rerank_top_k: int = 10
    max_context_pages: int = 8
    max_context_tokens: int = 6000
    min_score_threshold: float = 0.22
    vector_dim: int = 384
    summary_chars: int = 180
    rerank_lexical_alpha: float = 0.2
    rerank_provider: str = os.getenv("RERANK_PROVIDER", "lexical")
    rerank_model_name: str = os.getenv("RERANK_MODEL_NAME", "BAAI/bge-reranker-v2-m3")
    rerank_base_weight: float = float(os.getenv("RERANK_BASE_WEIGHT", "0.7"))
    rerank_batch_size: int = int(os.getenv("RERANK_BATCH_SIZE", "16"))


@dataclass(frozen=True)
class QdrantConfig:
    enabled: bool = os.getenv("QDRANT_ENABLED", "false").lower() == "true"
    url: str = os.getenv("QDRANT_URL", "http://127.0.0.1:6333")
    api_key: str | None = os.getenv("QDRANT_API_KEY")
    collection: str = os.getenv("QDRANT_COLLECTION", "page_index_rag_chunks")
    timeout: float = float(os.getenv("QDRANT_TIMEOUT", "3.0"))


@dataclass(frozen=True)
class GenerationConfig:
    provider: str = os.getenv("GEN_PROVIDER", "extractive")
    api_base: str = os.getenv("GEN_API_BASE", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    api_key: str | None = os.getenv("GEN_API_KEY")
    model: str = os.getenv("GEN_MODEL", "qwen-plus")
    timeout: float = float(os.getenv("GEN_TIMEOUT", "20"))
    max_tokens: int = int(os.getenv("GEN_MAX_TOKENS", "512"))
    temperature: float = float(os.getenv("GEN_TEMPERATURE", "0.2"))


def _default_data_file() -> Path:
    base = Path(os.getenv("PAGE_INDEX_RAG_DATA_DIR", "data"))
    return base / "page_index_store.json"


@dataclass(frozen=True)
class AppSettings:
    app_name: str = "page-index-rag"
    app_env: str = os.getenv("APP_ENV", "dev")
    host: str = os.getenv("APP_HOST", "0.0.0.0")
    port: int = int(os.getenv("APP_PORT", "9000"))
    data_file: Path = _default_data_file()
    rag: RagConfig = RagConfig()
    qdrant: QdrantConfig = QdrantConfig()
    generation: GenerationConfig = GenerationConfig()
