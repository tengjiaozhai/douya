package com.tengjiao.douya.infrastructure.tool;

import com.tengjiao.douya.infrastructure.external.pageindexrag.PageIndexRagClient;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Python PageIndexRAG 的公共知识检索工具
 */
@Slf4j
public class PageIndexRagSearchTool {

    private final PageIndexRagClient pageIndexRagClient;

    public PageIndexRagSearchTool(PageIndexRagClient pageIndexRagClient) {
        this.pageIndexRagClient = pageIndexRagClient;
    }

    public record Request(String query) {
    }

    public record Response(String content) {
    }

    public Response search(Request request) {
        String query = request.query();
        log.info("[PageIndexRagTool] query={}", query);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("top_k", 8);
            payload.put("with_debug", true);

            Map<String, Object> result = pageIndexRagClient.query(payload);
            String answer = String.valueOf(result.getOrDefault("answer", ""));
            Object citationsObj = result.get("citations");
            String citations = formatCitations(citationsObj);
            Object debug = result.get("debug");
            log.info("[PageIndexRagTool] hit answerLength={} citations={} debug={}",
                    answer.length(),
                    citations.isBlank() ? 0 : citations.split("\n").length,
                    debug);

            if (answer.isBlank()) {
                return new Response("未在 PageIndexRAG 中找到有效内容。");
            }
            return new Response("根据 PageIndexRAG 检索结果：\n\n" + answer + (citations.isBlank() ? "" : "\n\n引用:\n" + citations));
        } catch (Exception e) {
            log.error("[PageIndexRagTool] query failed", e);
            return new Response("PageIndexRAG 检索失败，建议稍后重试。");
        }
    }

    @SuppressWarnings("unchecked")
    private String formatCitations(Object citationsObj) {
        if (!(citationsObj instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    Object doc = item.get("doc_id");
                    Object page = item.get("page_no");
                    Object chunk = item.get("chunk_id");
                    return String.format("- doc=%s, page=%s, chunk=%s", doc, page, chunk);
                })
                .collect(Collectors.joining("\n"));
    }
}

