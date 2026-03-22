#!/usr/bin/env python3
from __future__ import annotations

"""PageIndexRAG 本地查询脚本（给 Java 调用）。

把它当作一个“命令行版微服务”理解就行：
- Java 把查询参数写到 stdin（标准输入）。
- Python 读取参数，执行查询，再把结果写到 stdout（标准输出）。
- Java 读取 stdout 里的 JSON，当作工具返回结果。

输入协议（stdin，必须是 JSON 对象）：
{
  "query": "用户问题，必填",
  "top_k": 8,                  # 可选，召回数量，默认 8
  "with_debug": true,          # 可选，是否携带调试字段，默认 true
  "data_file": "xxx.json"      # 可选，索引文件路径
}

输出协议（stdout）：
- 成功：输出 QueryResponse 的 JSON（由 Pydantic 序列化）
- 失败：输出统一错误 JSON
  {"status":"FAILED","code":"PYTHON_SCRIPT_QUERY_FAILED","error":"..."}

退出码（给调用方判断进程是否成功）：
- 0：成功
- 1：失败
"""

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any


def _bootstrap_import_path() -> None:
    # __file__ 是“当前脚本文件的绝对路径”。
    # resolve() 会把软链接等情况也解析成真实路径，减少路径歧义。
    # parents[1] 表示向上两级目录：
    #   scripts/page_index_query.py -> python-rag
    app_root = Path(__file__).resolve().parents[1]
    # 为什么要改 sys.path：
    # 1) 本地手动运行时，工作目录通常没问题；
    # 2) 但被 Java 的 ProcessBuilder 调起时，工作目录不一定在 python-rag；
    # 3) 此时 Python 找不到 `app.*` 包，就会报 ModuleNotFoundError。
    # 所以这里主动把项目根目录加到模块搜索路径。
    if str(app_root) not in sys.path:
        # 插到最前面：优先使用当前仓库代码，而不是系统里可能同名的包。
        sys.path.insert(0, str(app_root))


# 必须先做路径引导，再导入 app.* 模块。
# 否则下面这些 import 在某些启动方式下会失败。
_bootstrap_import_path()

# noqa: E402 的意思是忽略“import 位置不在文件顶部”的 lint 提示。
# 这里是有意为之：先修正 sys.path，再导入项目内模块。
from app.core.config import RagConfig  # noqa: E402
from app.core.reranker import build_reranker  # noqa: E402
from app.models.schemas import QueryRequest  # noqa: E402
from app.services.page_index_rag_service import PageIndexRagService  # noqa: E402
from app.storage.repository import JsonRepository  # noqa: E402


def _default_data_file() -> Path:
    # 环境变量优先：便于不同环境使用不同数据目录（开发/测试/生产）。
    # 没有配置时，默认走 data 目录（相对当前工作目录）。
    base = Path(os.getenv("PAGE_INDEX_RAG_DATA_DIR", "data"))
    # 最终索引文件名固定，避免调用方每次都传文件名。
    return base / "page_index_store.json"


def _to_bool(value: Any, *, default: bool) -> bool:
    """把任意输入尽量安全地转成 bool。

    设计目标：调用方传错类型时，不让脚本崩，而是退回默认值。
    """
    # 情况 1：调用方没传这个字段（None） -> 用默认值
    if value is None:
        return default
    # 情况 2：本来就是 bool -> 直接返回
    if isinstance(value, bool):
        return value
    # 情况 3：字符串 -> 统一去空格 + 小写，再做常见真值/假值判断
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"1", "true", "yes", "y"}:
            return True
        if lowered in {"0", "false", "no", "n"}:
            return False
    # 情况 4：其他不认识的类型（比如 list/dict）-> 回退默认值
    return default


def _to_int(value: Any, *, default: int) -> int:
    """把输入转成正整数，不合法就回退默认值。"""
    # int(value) 可以把 "8" 这样的字符串转成 8。
    try:
        parsed = int(value)
        # top_k 必须是正整数；0 或负数都视为非法，回退默认值。
        return parsed if parsed > 0 else default
    # 任何异常（类型错误、值错误）都吞掉，避免脚本直接崩溃。
    except Exception:
        return default


def _read_payload_from_stdin() -> dict[str, Any]:
    """从 stdin 读取调用参数，并确保是“JSON 对象”格式。"""
    # read() 会一次性读完整个 stdin 内容。
    # Java 侧通常会把一整段 JSON 字符串写进来。
    raw = sys.stdin.read()
    # 空字符串说明调用方没有传内容，这是协议错误。
    if not raw.strip():
        raise ValueError("stdin payload is empty")

    # 把 JSON 文本解析成 Python 对象。
    # 如果 JSON 格式错误，这里会抛 JSONDecodeError，外层统一捕获后返回错误 JSON。
    payload = json.loads(raw)

    # 这里强约束为 dict（键值对象），因为后面逻辑都用 payload.get("xxx")。
    # 若传的是数组/字符串等类型，会导致语义混乱，所以直接报错更清晰。
    if not isinstance(payload, dict):
        raise ValueError("payload must be a json object")
    return payload


def _build_service(data_file: Path) -> PageIndexRagService:
    """构造查询服务实例。这个函数只负责“组装依赖”，不做查询。"""
    # 读取 RAG 配置（阈值、召回参数等在这里生效）。
    rag_cfg = RagConfig()

    # 用本地 JSON 文件做存储仓库（不依赖外部向量数据库）。
    repo = JsonRepository(data_file)

    # 构建重排器：会根据环境选择可用实现（例如 lexical 或降级策略）。
    reranker = build_reranker(rag_cfg)

    # generator=None 表示“只检索，不生成答案”。
    # 这样做的好处：
    # 1) 不依赖外部模型 API Key；
    # 2) 不依赖网络，稳定性更高；
    # 3) 工具职责单一：只负责查资料和返回引用。
    return PageIndexRagService(repo, rag_cfg, reranker=reranker, generator=None)


def main() -> int:
    # argparse 的作用：
    # 1) 给脚本保留标准 CLI 入口；
    # 2) 参数不合法时，自动输出帮助并返回非 0。
    parser = argparse.ArgumentParser(description="PageIndexRAG query script")

    # 当前只支持一种模式：json-stdin（Java 通过 stdin 传参）。
    # 后续若要支持“读取本地 JSON 文件”，可在这里新增 mode。
    parser.add_argument("--mode", choices=["json-stdin"], default="json-stdin")

    # 这里虽然没用到 args，但 parse_args() 会帮我们完成参数校验。
    _ = parser.parse_args()

    try:
        # 步骤 1：读取请求体（stdin JSON）并做基本校验。
        payload = _read_payload_from_stdin()

        # query 是唯一必填字段：没有问题就没有检索目标。
        query = str(payload.get("query", "")).strip()
        if not query:
            raise ValueError("query is required")

        # 步骤 2：归一化可选参数。
        # 即使调用方传错类型，也尽量兜底到默认值，避免线上抖动。
        top_k = _to_int(payload.get("top_k"), default=8)
        with_debug = _to_bool(payload.get("with_debug"), default=True)

        # 步骤 3：确定索引文件路径。
        # - 传了 data_file：优先用调用方传入路径；
        # - 没传：走默认路径 data/page_index_store.json。
        # expanduser() 支持把 ~/xxx 展开成用户目录；
        # resolve() 把路径规范化成绝对路径，减少路径歧义。
        data_file_raw = payload.get("data_file")
        data_file = Path(str(data_file_raw)).expanduser().resolve() if data_file_raw else _default_data_file()

        # 步骤 4：执行查询，并输出标准 JSON 到 stdout。
        service = _build_service(data_file)
        response = service.query(QueryRequest(query=query, top_k=top_k, with_debug=with_debug))

        # model_dump_json() 是 Pydantic 的序列化方法，输出稳定 JSON 字符串。
        print(response.model_dump_json())
        return 0
    except Exception as exc:  # pragma: no cover - integration path
        # 任何异常都统一转成结构化 JSON，方便 Java 机器可读处理。
        # ensure_ascii=False 让中文错误信息不被转义成 \uXXXX。
        error = {"status": "FAILED", "code": "PYTHON_SCRIPT_QUERY_FAILED", "error": str(exc)}
        print(json.dumps(error, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    # Python 脚本约定：SystemExit(0) 代表成功，非 0 代表失败。
    # Java 侧可用 process.exitValue() 判断调用是否成功。
    raise SystemExit(main())
