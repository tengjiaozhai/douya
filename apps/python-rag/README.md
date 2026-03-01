# python-rag

`pageIndexRag` 的 Python 实现（M1 版本）。

## 当前能力（M1）

- `POST /api/rag/page-index/ingest`：文档入库（页级 + 子块）
- `POST /api/rag/page-index/query`：混合检索（dense + sparse + RRF）
- `GET /api/rag/page-index/status`：索引状态
- `GET /health`：健康检查

## 目录

- `app/api`：HTTP 路由
- `app/core`：配置与检索打分工具
- `app/indexing`：分页/切块/分词
- `app/models`：Pydantic DTO
- `app/services`：PageIndexRag 核心服务
- `app/storage`：JSON 索引存储
- `tests`：最小测试

## 使用 conda 启动

```bash
cd apps/python-rag
conda env create -f environment.yml
conda activate douya-page-index-rag
./scripts/run_dev.sh
```

服务默认端口：`9000`

### 可选：启用 Qdrant 混合检索

默认关闭。开启方式：

```bash
export QDRANT_ENABLED=true
export QDRANT_URL=http://127.0.0.1:6333
export QDRANT_COLLECTION=page_index_rag_chunks
./scripts/run_dev.sh
```

说明：

- 若 Qdrant 不可用，服务会自动回退本地检索，不影响接口可用性。
- 当前仍会保留本地 JSON 快照用于状态与调试。

### 可选：启用 BGE Reranker

默认 `RERANK_PROVIDER=lexical`。如需启用 BGE：

```bash
conda activate douya-page-index-rag
pip install FlagEmbedding
export RERANK_PROVIDER=bge
export RERANK_MODEL_NAME=BAAI/bge-reranker-v2-m3
./scripts/run_dev.sh
```

说明：

- 若未安装 `FlagEmbedding`，服务会自动回退到 lexical reranker。

### 可选：启用 LLM 生成器（OpenAI-compatible）

默认 `GEN_PROVIDER=extractive`（本地抽取式生成）。如需启用真实生成：

```bash
export GEN_PROVIDER=openai-compatible
export GEN_API_BASE=https://dashscope.aliyuncs.com/compatible-mode/v1
export GEN_API_KEY=<YOUR_API_KEY>
export GEN_MODEL=qwen-plus
./scripts/run_dev.sh
```

说明：

- 生成器会在检索+重排后使用证据页段生成自然语言答案。
- 若 API Key 缺失或初始化失败，自动回退 `extractive`。

## API 示例

### 1) Ingest

```bash
curl -X POST 'http://127.0.0.1:9000/api/rag/page-index/ingest' \
  -H 'Content-Type: application/json' \
  -d '{
    "doc_name": "样例文档",
    "content": "第一页内容...\f第二页内容...",
    "metadata": {"source": "demo"},
    "version": "v1"
  }'
```

### 2) Query

```bash
curl -X POST 'http://127.0.0.1:9000/api/rag/page-index/query' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "第二页讲了什么？",
    "top_k": 5,
    "with_debug": true
  }'
```

### 3) Status

```bash
curl 'http://127.0.0.1:9000/api/rag/page-index/status'
```

## M1 边界说明

- 当前使用本地 JSON 作为索引存储，便于快速验证流程。
- 当前回答为“检索型拼接回答”，未接入生成式 LLM。
- M2 已支持可选 Qdrant 混合检索、可选 BGE reranker、可选 LLM 生成器（均支持自动降级）。
