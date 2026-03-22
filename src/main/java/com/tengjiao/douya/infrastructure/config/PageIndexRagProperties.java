package com.tengjiao.douya.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PageIndexRAG Python 脚本工具配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "page-index-rag")
public class PageIndexRagProperties {

    /**
     * 是否启用 PageIndexRAG PythonTool 脚本调用
     */
    private boolean enabled = false;

    /**
     * 调用本地 Python 脚本使用的解释器命令。
     */
    private String pythonCommand = "python3";

    /**
     * 固定 Python 可执行文件路径（建议指向 conda 环境中的 python）。
     * 例如：/opt/anaconda3/envs/douya-page-index-rag/bin/python
     */
    private String pythonExecutable;

    /**
     * PageIndexRAG 查询脚本路径（相对项目根目录或绝对路径）。
     */
    private String queryScript = "apps/python-rag/scripts/page_index_query.py";

    /**
     * PageIndexRAG JSON 入库脚本路径。
     */
    private String ingestScript = "apps/python-rag/scripts/page_index_ingest.py";

    /**
     * PageIndexRAG 文件入库脚本路径。
     */
    private String ingestFileScript = "apps/python-rag/scripts/page_index_ingest_file.py";

    /**
     * PageIndexRAG 状态脚本路径。
     */
    private String statusScript = "apps/python-rag/scripts/page_index_status.py";

    /**
     * 固定数据文件路径（可选）。
     * 若不配置，脚本会回退 PAGE_INDEX_RAG_DATA_DIR/data 默认规则。
     */
    private String dataFile;

    /**
     * Python 脚本调用超时（秒）。
     */
    private int pythonTimeoutSeconds = 60;
}
