# 豆芽 (Douya) - SliceMaster 文档切片技能

**SliceMaster** 是专为“豆芽”AI Agent 系统设计的文档处理组件。它负责高效摄取、清洗并切分大型文档（支持原生 PDF 与扫描件），生成语义连贯的 JSONL 数据，供 RAG 知识库使用。

## 🌟 核心功能

- **智能切片**: 基于 `RecursiveCharacterTextSplitter`，优先保持段落和语义结构的完整性。
- **OCR 集成**: 自动检测扫描版/图片型 PDF，并使用 `rapidocr-pdf` 进行高精度文字提取。
- **元数据丰富**: 每个切片包含源文件、页码、处理时间戳等可追溯信息。
- **格式无关**: 标准化输出为 JSONL 格式，易于后续向量化处理。

## 📂 项目结构

```
split-document/
├── alreadySplit/          # 输出目录 (生成的 JSONL 文件)
├── scripts/               # Python 脚本
│   ├── split_document.py  # 主程序：文档切片与 OCR
│   └── ...                # 测试与辅助脚本
├── split-document/
│   └── skills/
│       └── SKILL.md       # SliceMaster 技能定义文档 (Douya 规范)
└── waitingForSpliting/    # 输入目录 (待处理的 PDF 文件)
```

## 🚀 快速开始

### 1. 环境准备

建议使用 Conda 管理环境。

```bash
# 创建并激活环境
conda create -n douya-slicer python=3.10
conda activate douya-slicer

# 安装依赖
pip install langchain langchain-community langchain-text-splitters pypdf rapidocr-pdf pymupdf
```

_注意: `rapidocr-pdf` 用于处理扫描件，`pymupdf` 是其底层依赖。_

### 2. 运行切片工具

**基本用法**:
将 PDF 文件放入 `waitingForSpliting` 目录，然后运行：

```bash
python scripts/split_document.py
```

**命令行参数**:
支持指定输入文件和输出目录：

```bash
python scripts/split_document.py -i <输入文件路径> -o <输出目录路径>
```

**示例**:

```bash
python scripts/split_document.py -i waitingForSpliting/贵金属材料学.pdf
```

### 3. 查看结果

处理完成后，结果将保存在 `alreadySplit` 目录下，文件名为 `<原文件名>_split.jsonl`。

**数据格式示例**:

```json
{
  "content": "...文档切片内容...",
  "metadata": {
    "source_id": "uuid...",
    "source_file": "贵金属材料学.pdf",
    "page": 5,
    "chunk_index": 10,
    "processor": "SliceMaster-v1",
    "timestamp": "2026-02-11T15:30:00"
  }
}
```

## 🛠️ 开发说明

- **核心逻辑**: `scripts/split_document.py`
- **OCR 策略**: 脚本会首先尝试使用 `pypdf` 提取文本。如果提取内容为空（判定为扫描件），则自动回退使用 `rapidocr-pdf` 引擎。
- **技能定义**: 详见 `split-document/skills/SKILL.md`。

---

_Created for Douya AI Agent System._
