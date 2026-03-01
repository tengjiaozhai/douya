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
    private boolean enabled = true;

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
}

