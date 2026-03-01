from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Protocol

import httpx

from app.core.config import GenerationConfig

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class EvidenceSnippet:
    page_no: int
    text: str


class AnswerGenerator(Protocol):
    name: str

    def generate(self, query: str, snippets: list[EvidenceSnippet]) -> str:
        ...


class ExtractiveGenerator:
    name = "extractive"

    def generate(self, query: str, snippets: list[EvidenceSnippet]) -> str:
        if not snippets:
            return f"未检索到与问题“{query}”高相关的页面，请尝试更具体的关键词。"
        lines = [f"[p{s.page_no}] {s.text[:220]}" for s in snippets[:6]]
        return "以下是基于 pageIndexRAG 检索到的关键信息：\n" + "\n".join(lines)


class OpenAICompatibleGenerator:
    name = "openai-compatible"

    def __init__(self, cfg: GenerationConfig) -> None:
        if not cfg.api_key:
            raise RuntimeError("GEN_API_KEY is required for openai-compatible generation.")
        self.cfg = cfg
        self.client = httpx.Client(timeout=cfg.timeout)

    def generate(self, query: str, snippets: list[EvidenceSnippet]) -> str:
        if not snippets:
            return f"未检索到与问题“{query}”高相关的页面，请尝试更具体的关键词。"
        evidence = "\n".join([f"[p{s.page_no}] {s.text[:260]}" for s in snippets[:8]])
        prompt = (
            "你是 pageIndexRAG 助手。仅基于给定证据回答，禁止编造。"
            "回答中至少包含一个形如 [p12] 的页码引用。\n"
            f"问题：{query}\n"
            f"证据：\n{evidence}"
        )
        url = self.cfg.api_base.rstrip("/") + "/chat/completions"
        headers = {"Authorization": f"Bearer {self.cfg.api_key}"}
        payload = {
            "model": self.cfg.model,
            "messages": [
                {"role": "system", "content": "You answer with grounded citations."},
                {"role": "user", "content": prompt},
            ],
            "max_tokens": self.cfg.max_tokens,
            "temperature": self.cfg.temperature,
        }
        resp = self.client.post(url, headers=headers, json=payload)
        resp.raise_for_status()
        data = resp.json()
        return data["choices"][0]["message"]["content"].strip()


def build_generator(cfg: GenerationConfig) -> AnswerGenerator:
    provider = cfg.provider.lower().strip()
    if provider in {"openai", "openai-compatible", "dashscope"}:
        try:
            return OpenAICompatibleGenerator(cfg)
        except Exception as exc:
            logger.warning("generator_init_fallback provider=%s reason=%s", provider, exc)
            return ExtractiveGenerator()
    return ExtractiveGenerator()

