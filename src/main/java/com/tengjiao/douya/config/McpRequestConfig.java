package com.tengjiao.douya.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 自定义mcp连接
 *
 * @author 沈鸣杰
 * @since 2026-01-16 21:00
 */
@Configuration
public class McpRequestConfig {
    @Value("${aigohotel-mcp.api-key}")
    private String apiKey;
    @Bean
    public McpSyncHttpClientRequestCustomizer mcpAsyncHttpClientRequestCustomizer() {
        // 可以设置默认的 headers，但 Authorization 会从当前请求中动态获取
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return new HeaderSyncHttpRequestCustomizer(headers);
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2) // 尝试使用 HTTP/2 以避免 HTTP/1.1 chunked encoding 问题
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }
}
