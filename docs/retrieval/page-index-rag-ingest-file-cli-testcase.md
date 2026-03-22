# PageIndexRAG `ingestFile` 终端直调脚本测试用例

## 1. 目的

不经过 Java HTTP 接口，直接在终端调用 Python 脚本 `apps/python-rag/scripts/page_index_ingest_file.py`，验证 `ingestFile` 等价路径是否可用。

## 2. 前置条件

1. Python 环境可用（示例）：`/opt/anaconda3/envs/douya-page-index-rag/bin/python`
2. 已安装依赖：`apps/python-rag/requirements.txt`
3. 目标文件存在（PDF/DOCX/TXT/Markdown）
4. 建议先进入仓库根目录：`/Users/shenmingjie/workSpace/douya`

## 3. 快速可执行命令（文件路径直传，无需 JSON）

### 3.1 在仓库根目录执行（推荐）

```bash
cd /Users/shenmingjie/workSpace/douya

/opt/anaconda3/envs/douya-page-index-rag/bin/python \
apps/python-rag/scripts/page_index_ingest_file.py \
--file-path src/main/resources/document/每日美味食谱手册.pdf
```

### 3.2 在任意目录执行（绝对路径）

```bash
/opt/anaconda3/envs/douya-page-index-rag/bin/python \
/Users/shenmingjie/workSpace/douya/apps/python-rag/scripts/page_index_ingest_file.py \
--file-path /Users/shenmingjie/workSpace/douya/src/main/resources/document/每日美味食谱手册.pdf
```

### 3.3 常用可选参数（按需）

```bash
/opt/anaconda3/envs/douya-page-index-rag/bin/python \
apps/python-rag/scripts/page_index_ingest_file.py \
--file-path src/main/resources/document/每日美味食谱手册.pdf \
--doc-id doc_menu_001 \
--doc-name 每日美味食谱手册.pdf \
--version v2 \
--metadata-json '{"source":"cli-test","department":"ops","tags":["menu","2026Q1"]}' \
--data-file /tmp/page_index_store.json
```

预期：

1. 脚本标准输出返回 JSON。
2. 返回体包含 `doc_id`、`version`、`page_count`、`chunk_count`。
3. 若失败会返回 `status=FAILED` 和 `error` 字段。

## 4. PyCharm 调试方式（推荐）

### 4.1 配置 PyCharm Run/Debug Configuration（仅传文件路径）

1. 打开 `Run | Edit Configurations...`，新建 `Python` 配置。
2. `Script path` 填：
   `/Users/shenmingjie/workSpace/douya/apps/python-rag/scripts/page_index_ingest_file.py`
3. `Parameters` 填：
   `--file-path /Users/shenmingjie/workSpace/douya/src/main/resources/document/每日美味食谱手册.pdf`
4. `Python interpreter` 选择：
   `/opt/anaconda3/envs/douya-page-index-rag/bin/python`
5. `Working directory` 填：
   `/Users/shenmingjie/workSpace/douya`
6. 需要调试 OCR/上传时，可在 `Environment variables` 补充业务环境变量（如 `PAGE_INDEX_RAG_OSS_UPLOAD_ENABLED`）。
7. 若要显式调试版本/元数据，可追加参数：
   `--doc-id doc_menu_001 --version v2 --metadata-json "{\"source\":\"pycharm-debug\"}"`

### 4.2 启动 Debug 与查看结果

1. 在 `apps/python-rag/scripts/page_index_ingest_file.py` 的 `main()`、`parse_uploaded_document(...)`、`service.ingest(req)` 处打断点。
2. 点击 `Debug` 启动后，查看 `file_path`、`file_name`、`merged_meta`、`parsed.pages` 等变量。
3. 运行结束后在控制台可看到 JSON 输出，成功时包含 `doc_id/version/page_count/chunk_count`。

## 5. 用例 A：首次入库（自动生成 `doc_id`，路径直传）

```bash
/opt/anaconda3/envs/douya-page-index-rag/bin/python \
apps/python-rag/scripts/page_index_ingest_file.py \
--file-path /Users/shenmingjie/workSpace/douya/src/main/resources/document/每日美味食谱手册.pdf
```

预期：

1. 返回 JSON 且 `status` 为成功态。
2. 返回体包含 `doc_id`、`version`、`page_count`、`chunk_count`。

## 6. 用例 B：复用 `doc_id` 做版本更新（路径直传）

```bash
/opt/anaconda3/envs/douya-page-index-rag/bin/python \
apps/python-rag/scripts/page_index_ingest_file.py \
--file-path /Users/shenmingjie/workSpace/douya/src/main/resources/document/每日美味食谱手册.pdf \
--doc-id doc_menu_001 \
--doc-name 每日美味食谱手册.pdf \
--version v2 \
--metadata-json '{"source":"cli-test","change_ticket":"CM-2026-0318"}'
```

预期：

1. 返回 JSON 且 `status` 为成功态。
2. `doc_id` 为 `doc_menu_001`，`version` 为 `v2`。
3. 同 `doc_id` 的旧内容被覆盖为新版本内容。

## 7. 兼容模式（保留）：stdin / payload-file JSON 输入

仍支持历史调用方式：

1. `--mode json-stdin` + stdin 管道 JSON。
2. `--payload-file /tmp/payload.json` 从文件读取 JSON。

## 8. 常见失败与排查

1. `pypdf is required for PDF upload`：当前 Python 环境缺少 `pypdf`，执行：
   ` /opt/anaconda3/envs/douya-page-index-rag/bin/python -m pip install pypdf `
2. `file_path not found`：检查 `--file-path` 是否为正确绝对路径。
3. `file_path is not a regular file`：当前路径是目录而不是文件。
4. `stdin payload is empty`：你在 JSON 模式下没有通过管道输入。
5. 脚本路径错误：确认 `apps/python-rag/scripts/page_index_ingest_file.py` 存在。
