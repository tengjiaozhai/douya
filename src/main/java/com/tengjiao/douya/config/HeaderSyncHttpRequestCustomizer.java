package com.tengjiao.douya.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

/**
 * 自定义请求头
 *
 * @author 沈鸣杰
 * @since 2026-01-16 21:02
 */

public class HeaderSyncHttpRequestCustomizer implements McpSyncHttpClientRequestCustomizer {
    private final Map<String, String> headers;

    public HeaderSyncHttpRequestCustomizer(Map<String, String> headers) {
        this.headers = headers;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body, McpTransportContext context) {
        // 先添加固定的 headers
        if (!headers.isEmpty()) {
            headers.forEach((name, value) -> {
                if (!"Content-Length".equalsIgnoreCase(name)) {
                    builder.header(name, value);
                }
            });
        }

    }
}
