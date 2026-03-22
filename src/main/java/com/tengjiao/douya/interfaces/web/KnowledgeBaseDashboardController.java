package com.tengjiao.douya.interfaces.web;

import com.tengjiao.douya.infrastructure.config.ChromaProperties;
import com.tengjiao.douya.infrastructure.external.pageindexrag.PageIndexRagClient;
import com.tengjiao.douya.infrastructure.external.pageindexrag.PageIndexRagStoreService;
import com.tengjiao.douya.infrastructure.vectorstore.UserVectorApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人知识库仪表盘接口（Chroma + PageIndexRAG）。
 */
@Slf4j
@RestController
@RequestMapping("/douya/kb/dashboard")
@Tag(name = "个人知识库仪表盘")
@RequiredArgsConstructor
public class KnowledgeBaseDashboardController {

    private final UserVectorApp userVectorApp;
    private final ChromaProperties chromaProperties;
    private final PageIndexRagClient pageIndexRagClient;
    private final PageIndexRagStoreService pageIndexRagStoreService;

    @GetMapping("/overview")
    @Operation(summary = "获取知识库总览（Chroma + PageIndexRAG）")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated_at", Instant.now().toString());

        String collectionName = chromaProperties.getCollectionName();
        Map<String, Object> chroma = new LinkedHashMap<>();
        chroma.put("collection_name", collectionName);
        try {
            Map<String, Object> raw = userVectorApp.getChromaRawData(collectionName, 1, 0);
            chroma.put("total_chunks", raw.getOrDefault("total", 0));
        } catch (Exception e) {
            chroma.put("error", e.getMessage());
        }
        result.put("chroma", chroma);

        Map<String, Object> pageIndex = new LinkedHashMap<>();
        pageIndex.put("store_status", pageIndexRagStoreService.getStatus());
        try {
            pageIndex.put("script_status", pageIndexRagClient.status());
        } catch (Exception e) {
            pageIndex.put("script_status_error", e.getMessage());
        }
        result.put("page_index_rag", pageIndex);
        return result;
    }

    @GetMapping("/chroma/items")
    @Operation(summary = "分页查看 Chroma 知识条目")
    public Map<String, Object> chromaItems(
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String documentName
    ) {
        return userVectorApp.getDashboardItems(chromaProperties.getCollectionName(), limit, offset, keyword, documentName);
    }

    @GetMapping("/chroma/items/{id}")
    @Operation(summary = "查看单个 Chroma 条目详情")
    public Map<String, Object> chromaItem(@PathVariable String id) {
        return userVectorApp.getChunkById(chromaProperties.getCollectionName(), id);
    }

    @PutMapping("/chroma/items/{id}")
    @Operation(summary = "编辑更新单个 Chroma 条目")
    public Map<String, Object> updateChromaItem(
            @PathVariable String id,
            @RequestBody Map<String, Object> request
    ) {
        String content = toText(request.get("content"));
        Map<String, Object> metadata = toMetadataMap(request.get("metadata"));
        return userVectorApp.updateChunkById(chromaProperties.getCollectionName(), id, content, metadata);
    }

    @DeleteMapping("/chroma/items/{id}")
    @Operation(summary = "删除单个 Chroma 条目")
    public Map<String, Object> deleteChromaItem(@PathVariable String id) {
        return userVectorApp.deleteById(chromaProperties.getCollectionName(), id);
    }

    @GetMapping("/page-index/docs")
    @Operation(summary = "分页查看 PageIndexRAG 文档列表")
    public Map<String, Object> pageIndexDocs(
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset,
            @RequestParam(required = false) String keyword
    ) {
        return pageIndexRagStoreService.listDocuments(limit, offset, keyword);
    }

    @GetMapping("/page-index/docs/{docId}")
    @Operation(summary = "查看 PageIndexRAG 文档详情（含页面文本）")
    public Map<String, Object> pageIndexDocDetail(@PathVariable String docId) {
        return pageIndexRagStoreService.getDocumentDetail(docId);
    }

    @PutMapping("/page-index/docs/{docId}")
    @Operation(summary = "编辑更新 PageIndexRAG 文档（按 doc_id 覆盖更新）")
    public Map<String, Object> updatePageIndexDoc(
            @PathVariable String docId,
            @RequestBody Map<String, Object> request
    ) {
        try {
            Map<String, Object> detail = pageIndexRagStoreService.getDocumentDetail(docId);
            if (detail.containsKey("error")) {
                return detail;
            }

            Map<String, Object> doc = asMap(detail.get("document"));
            List<Map<String, Object>> pages = asListOfMap(detail.get("pages"));

            String docName = firstText(
                    toText(request.get("doc_name")),
                    toText(doc.get("doc_name")),
                    docId
            );
            String version = firstText(
                    toText(request.get("version")),
                    toText(doc.get("version")),
                    "v1"
            );

            Map<String, Object> metadataPatch = toMetadataMap(request.get("metadata"));
            Map<String, Object> metadata = toMetadataMap(doc.get("metadata"));
            metadata.putAll(metadataPatch);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("doc_id", docId);
            payload.put("doc_name", docName);
            payload.put("version", version);
            payload.put("metadata", metadata);

            String content = toText(request.get("content"));
            List<String> pageTexts = toStringList(request.get("pages"));
            if (content != null && !content.isBlank()) {
                payload.put("content", content);
            } else if (!pageTexts.isEmpty()) {
                payload.put("pages", pageTexts);
            } else {
                List<String> existingPages = pages.stream()
                        .map(page -> toText(page.get("page_text")))
                        .filter(text -> text != null && !text.isBlank())
                        .toList();
                if (existingPages.isEmpty()) {
                    return Map.of("status", "FAILED", "error", "未提供 content/pages，且当前文档无可回填页面内容");
                }
                payload.put("pages", existingPages);
            }

            return pageIndexRagClient.ingest(payload);
        } catch (Exception e) {
            log.error("kb_dashboard_update_page_index_doc_failed docId={} error={}", docId, e.getMessage(), e);
            return Map.of("status", "FAILED", "error", e.getMessage());
        }
    }

    @DeleteMapping("/page-index/docs/{docId}")
    @Operation(summary = "删除 PageIndexRAG 文档（直接修改本地 JSON 快照）")
    public Map<String, Object> deletePageIndexDoc(@PathVariable String docId) {
        return pageIndexRagStoreService.deleteDocument(docId);
    }

    @PostMapping("/page-index/docs")
    @Operation(summary = "新增 PageIndexRAG 文档（JSON 入库）")
    public Map<String, Object> createPageIndexDoc(@RequestBody Map<String, Object> request) {
        try {
            return pageIndexRagClient.ingest(request == null ? Map.of() : request);
        } catch (Exception e) {
            log.error("kb_dashboard_create_page_index_doc_failed error={}", e.getMessage(), e);
            return Map.of("status", "FAILED", "error", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMetadataMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    metadata.put(String.valueOf(k), v);
                }
            });
            return metadata;
        }
        return new LinkedHashMap<>();
    }

    private String toText(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMap(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                result.add(asMap(item));
            }
            return result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    result.put(String.valueOf(k), v);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }
}

