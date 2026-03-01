from app.core.config import GenerationConfig
from app.core.generator import ExtractiveGenerator, build_generator


def test_extractive_generator_returns_page_markers() -> None:
    gen = ExtractiveGenerator()
    answer = gen.generate(
        "问题",
        snippets=[],
    )
    assert "未检索到" in answer


def test_build_generator_fallback_without_api_key() -> None:
    cfg = GenerationConfig(provider="openai-compatible", api_key=None)
    gen = build_generator(cfg)
    assert gen.name == "extractive"

