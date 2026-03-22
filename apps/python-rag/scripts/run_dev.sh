#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ "${PAGE_INDEX_RAG_ENABLE_HTTP_SERVICE:-false}" != "true" ]]; then
  echo "Deprecated: python-rag HTTP 接口服务默认禁用。请改用 scripts/page_index_*.py 脚本模式。"
  echo "如需临时兼容旧接口，可设置 PAGE_INDEX_RAG_ENABLE_HTTP_SERVICE=true 后再启动。"
  exit 1
fi

uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
