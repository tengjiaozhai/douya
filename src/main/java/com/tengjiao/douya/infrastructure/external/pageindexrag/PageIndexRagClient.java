package com.tengjiao.douya.infrastructure.external.pageindexrag;

import com.tengjiao.douya.infrastructure.config.PageIndexRagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * PageIndexRAG Python 服务客户端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageIndexRagClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Qualifier("pageIndexRagRestClient")
    private final RestClient restClient;
    private final PageIndexRagProperties properties;

    public Map<String, Object> status() {
        ensureEnabled();
        log.info("page_index_rag_status_request baseUrl={}", properties.getBaseUrl());
        return restClient.get()
                .uri("/api/rag/page-index/status")
                .retrieve()
                .body(MAP_TYPE);
    }

    public Map<String, Object> ingest(Map<String, Object> request) {
        ensureEnabled();
        log.info("page_index_rag_ingest_request baseUrl={} keys={}", properties.getBaseUrl(), request.keySet());
        return restClient.post()
                .uri("/api/rag/page-index/ingest")
                .body(request)
                .retrieve()
                .body(MAP_TYPE);
    }

    public Map<String, Object> query(Map<String, Object> request) {
        ensureEnabled();
        log.info("page_index_rag_query_request baseUrl={} keys={}", properties.getBaseUrl(), request.keySet());
        return restClient.post()
                .uri("/api/rag/page-index/query")
                .body(request)
                .retrieve()
                .body(MAP_TYPE);
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("page-index-rag.enabled=false, Java proxy is disabled");
        }
    }
}

