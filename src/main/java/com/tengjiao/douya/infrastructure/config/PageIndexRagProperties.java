package com.tengjiao.douya.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * PageIndexRAG Python 服务配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "page-index-rag")
public class PageIndexRagProperties {

    /**
     * 是否启用 Java -> Python PageIndexRAG 代理
     */
    private boolean enabled = false;

    /**
     * Python 服务地址，例如: http://127.0.0.1:9000
     */
    private String baseUrl = "http://127.0.0.1:9000";

    /**
     * 连接超时
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * 读取超时
     */
    private Duration readTimeout = Duration.ofSeconds(30);

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
     * Python 脚本调用超时（秒）。
     */
    private int pythonTimeoutSeconds = 30;
}
