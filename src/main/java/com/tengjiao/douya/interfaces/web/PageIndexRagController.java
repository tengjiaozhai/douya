package com.tengjiao.douya.interfaces.web;

import com.tengjiao.douya.infrastructure.external.pageindexrag.PageIndexRagClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * PageIndexRAG 代理接口（Java -> Python）
 */
@Slf4j
@RestController
@RequestMapping("/douya/page-index-rag")
@Tag(name = "PageIndexRAG 代理接口")
@RequiredArgsConstructor
public class PageIndexRagController {

    private final PageIndexRagClient pageIndexRagClient;

    @GetMapping("/status")
    @Operation(summary = "查询 Python PageIndexRAG 状态")
    public Map<String, Object> status() {
        try {
            return pageIndexRagClient.status();
        } catch (Exception e) {
            log.error("page_index_rag_status_failed error={}", e.getMessage(), e);
            return error("STATUS_FAILED", e.getMessage());
        }
    }

    @PostMapping("/ingest")
    @Operation(summary = "代理 Python PageIndexRAG 入库接口")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> request) {
        try {
            return pageIndexRagClient.ingest(request);
        } catch (Exception e) {
            log.error("page_index_rag_ingest_failed error={}", e.getMessage(), e);
            return error("INGEST_FAILED", e.getMessage());
        }
    }

    @PostMapping("/query")
    @Operation(summary = "代理 Python PageIndexRAG 查询接口")
    public Map<String, Object> query(@RequestBody Map<String, Object> request) {
        try {
            return pageIndexRagClient.query(request);
        } catch (Exception e) {
            log.error("page_index_rag_query_failed error={}", e.getMessage(), e);
            return error("QUERY_FAILED", e.getMessage());
        }
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("status", "FAILED");
        map.put("error", message);
        return map;
    }
}

