package com.tengjiao.douya.config;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

import java.net.http.HttpClient;

/**
 * MCP 客户端配置
 * 用于注入认证信息并修复 Chunked Encoding 传输问题 (READING_LENGTH)
 */
@Configuration
public class McpConfig {

    @Bean
    public WebClientCustomizer mcpWebClientCustomizer() {
        // 强制使用 HTTP/1.1 以修复 JDK HttpClient 在处理分块流时的 state: READING_LENGTH 错误
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        return webClientBuilder -> webClientBuilder
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer mcp_396c5b8971994548b8cf6f1e9614b30f")
                .defaultHeader("Content-Type", "application/json");
    }
}
