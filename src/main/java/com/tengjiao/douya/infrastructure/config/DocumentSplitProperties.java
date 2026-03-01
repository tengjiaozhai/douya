package com.tengjiao.douya.infrastructure.config;

import com.tengjiao.douya.domain.eating.model.DocumentSplitStrategy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PDF 切分配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "douya.document.split")
public class DocumentSplitProperties {

    /**
     * 默认切分策略
     */
    private DocumentSplitStrategy defaultStrategy = DocumentSplitStrategy.JAVA;

    /**
     * Python 解释器命令
     */
    private String pythonCommand = "python3";

    /**
     * Python 切分脚本路径（相对项目根目录）
     */
    private String pythonScript = "apps/split-document/scripts/split_document.py";

    /**
     * Python 子进程超时（秒）
     */
    private long pythonTimeoutSeconds = 60;
}
