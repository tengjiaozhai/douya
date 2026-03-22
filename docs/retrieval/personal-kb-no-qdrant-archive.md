# 个人知识库无 Qdrant 方案归档

## 背景

本次改造目标是统一个人知识库检索路径，并明确系统边界：

1. 不使用 Qdrant。
2. 检索底座采用 Java + Chroma。
3. Python `page-index-rag` 仅负责文档解析与页级引用组织，不连接外部向量数据库。

## 为什么之前会出现 Qdrant 依赖

历史上 `page-index-rag` 曾预留过可选 Qdrant 分支，目的是在数据规模上升时作为混合检索加速路径。  
该分支不是硬依赖，但会增加理解和维护成本。当前架构决策明确后，已从代码和文档中清理。

## 最终职责边界

### Java + Chroma（主检索链路）

- 承担公共知识与个人知识的检索。
- 承担工具层 `public_search` 的线上调用。
- 承担后续多路召回（dense/sparse/rule/recency）与融合的主实现。

### Python page-index-rag（解析与引用链路）

- 接收文档，按页切分并构建页级/块级结构。
- 通过本地 JSON 快照进行检索与引用返回。
- 保留 `ingest/query/status` 接口以支持页级引用能力。

## 迁移策略

1. 先清理 Python 侧 Qdrant 代码、依赖和配置。
2. 再将 Java `public_search` 固定到 Chroma 检索路径。
3. 最后完成全仓检查，确保不再存在 Qdrant 运行时逻辑。

## 回滚策略

若回归出现问题，可仅在 Java 层恢复到旧工具路由，不需要恢复 Qdrant。  
Python `page-index-rag` 保持本地检索能力，仍可独立提供页级引用结果。

## 验收要点

1. `apps/python-rag` 不再依赖 `qdrant-client`。
2. `page-index-rag` 在无 Qdrant 环境可正常启动与查询。
3. `public_search` 默认且仅走 Java + Chroma。
4. 仓库中不再出现 Qdrant 运行配置和启用说明。
