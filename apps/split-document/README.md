# split-document (Integrated in douya)

来源仓库：`https://github.com/tengjiaozhai/split-document.git`

本目录已并入 `douya` 单仓统一管理，未保留上游 `.git` 元数据。

## 目录

- `scripts/split_document.py`: 文档切分脚本（支持 Java 调用的 `json-stdin` 模式）
- `waitingForSpliting/`: 兼容上游的输入目录
- `alreadySplit/`: 兼容上游的输出目录
- `README.upstream.md`: 上游说明备份

## Java 调用模式

Java 服务通过以下命令调用：

```bash
python3 apps/split-document/scripts/split_document.py --mode json-stdin
```

输入（stdin）是 pages JSON，输出（stdout）为 chunk JSON。

## 手工模式（兼容）

```bash
python3 apps/split-document/scripts/split_document.py -i <text-file> -o apps/split-document/alreadySplit
```
