# 个人知识库仪表盘设计归档（Chroma + PageIndexRAG）

## 1. 背景与目标

为降低知识库运维门槛，新增统一仪表盘，覆盖两类存储：

1. Chroma 向量库（片段级）
2. PageIndexRAG 本地 JSON 知识库（文档/页级）

目标：

1. 一屏查看当前知识库内容与规模。
2. 支持在线编辑并更新现有知识条目。
3. 支持按条目/文档删除，便于快速清理错误数据。

## 2. 架构边界

### 2.1 Chroma（主检索库）

1. 数据粒度：向量分片（chunk）。
2. 更新方式：按 `id` 更新 `content` 后重算 embedding，再执行 upsert。
3. 删除方式：按 `id` 物理删除向量分片。

### 2.2 PageIndexRAG（页级引用库）

1. 数据粒度：`document -> pages -> chunks`（本地 JSON 快照）。
2. 更新方式：沿用 `ingest` 逻辑，按 `doc_id` 覆盖更新。
3. 删除方式：直接删除快照中的 `documents/pages/chunks` 关联数据。

## 3. 后端接口设计

统一前缀：`/api/douya/kb/dashboard`

### 3.1 总览

1. `GET /overview`
2. 返回 Chroma 分片总数 + PageIndexRAG docs/pages/chunks 状态。

### 3.2 Chroma 管理

1. `GET /chroma/items`
2. `GET /chroma/items/{id}`
3. `PUT /chroma/items/{id}`
4. `DELETE /chroma/items/{id}`

说明：

1. `PUT` 请求体包含 `content` 与可选 `metadata` patch。
2. 更新时会重算 embedding 并使用同 `id` upsert。

### 3.3 PageIndexRAG 管理

1. `GET /page-index/docs`
2. `GET /page-index/docs/{docId}`
3. `POST /page-index/docs`
4. `PUT /page-index/docs/{docId}`
5. `DELETE /page-index/docs/{docId}`

说明：

1. `PUT` 复用 Python `ingest`，按 `doc_id` 执行“先清旧、再写新”的覆盖更新。
2. 若未显式传 `pages/content`，默认回填现有页面文本，避免误清空。

## 4. 前端页面设计

页面入口：`/api/kb-dashboard.html`

布局分为左右双面板：

1. 左侧 Chroma：列表、筛选、详情编辑、保存更新、删除条目。
2. 右侧 PageIndexRAG：文档列表、文档详情、页面内容编辑、新建/更新/删除。

关键交互：

1. 顶部 summary 卡片展示集合名、分片总量、文档总量、页块统计。
2. 编辑区支持 JSON metadata 直接修改。
3. PageIndexRAG 页面文本通过 `---PAGE---` 分隔，便于可视化编辑多页。

## 5. 数据与一致性策略

1. Chroma 更新以 `id` 为主键，保证单条可回写。
2. PageIndexRAG 更新以 `doc_id` 为主键，保证文档级覆盖一致性。
3. 编辑后在页面内触发刷新，确保视图与实际存储一致。

## 6. 风险与限制

1. Chroma 列表当前采用“全量扫描 + 本地过滤 + 分页”，适合个人/中小规模库，超大规模需改为索引化查询。
2. PageIndexRAG 删除为本地快照删除，不带回滚日志；生产建议补审计流水。
3. metadata 为自由 JSON，调用方需遵守字段规范，避免脏数据扩大。

## 7. 后续演进建议

1. 增加操作审计（谁在何时改了什么）。
2. 增加字段级校验模板（metadata schema）。
3. 增加批量操作（批量打标签、批量归档、批量删除）。
4. 增加“编辑预览 + 差异对比”能力，减少误操作。

