package com.tengjiao.douya.infrastructure.external.pageindexrag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tengjiao.douya.infrastructure.config.PageIndexRagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读取/写入 PageIndexRAG 本地 JSON 存储快照（data/page_index_store.json）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageIndexRagStoreService {

    private static final String ENV_DATA_FILE = "PAGE_INDEX_RAG_DATA_FILE";
    private static final String ENV_DATA_DIR = "PAGE_INDEX_RAG_DATA_DIR";
    private static final String DEFAULT_DATA_FILE_NAME = "page_index_store.json";

    private final PageIndexRagProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> getStatus() {
        Map<String, Object> snapshot = loadSnapshot();
        Map<String, Object> docs = asMap(snapshot.get("documents"));
        Map<String, Object> pages = asMap(snapshot.get("pages"));
        Map<String, Object> chunks = asMap(snapshot.get("chunks"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docs", docs.size());
        result.put("pages", pages.size());
        result.put("chunks", chunks.size());
        result.put("updated_at", snapshot.get("updated_at"));
        result.put("data_file", resolveDataFile().toString());
        return result;
    }

    public Map<String, Object> listDocuments(Integer limit, Integer offset, String keyword) {
        int pageSize = (limit != null && limit > 0) ? limit : 20;
        int pageOffset = (offset != null && offset >= 0) ? offset : 0;

        Map<String, Object> snapshot = loadSnapshot();
        Map<String, Object> docs = asMap(snapshot.get("documents"));
        Map<String, Object> pages = asMap(snapshot.get("pages"));
        Map<String, Object> chunks = asMap(snapshot.get("chunks"));

        Map<String, Integer> pageCountByDoc = new LinkedHashMap<>();
        for (Object pageObj : pages.values()) {
            Map<String, Object> page = asMap(pageObj);
            String docId = String.valueOf(page.getOrDefault("doc_id", ""));
            if (docId.isBlank()) {
                continue;
            }
            pageCountByDoc.put(docId, pageCountByDoc.getOrDefault(docId, 0) + 1);
        }

        Map<String, Integer> chunkCountByDoc = new LinkedHashMap<>();
        for (Object chunkObj : chunks.values()) {
            Map<String, Object> chunk = asMap(chunkObj);
            String docId = String.valueOf(chunk.getOrDefault("doc_id", ""));
            if (docId.isBlank()) {
                continue;
            }
            chunkCountByDoc.put(docId, chunkCountByDoc.getOrDefault(docId, 0) + 1);
        }

        List<Map<String, Object>> allItems = new ArrayList<>();
        for (Map.Entry<String, Object> entry : docs.entrySet()) {
            String docId = entry.getKey();
            Map<String, Object> doc = asMap(entry.getValue());
            Map<String, Object> item = new LinkedHashMap<>(doc);
            item.putIfAbsent("doc_id", docId);
            item.put("page_count", pageCountByDoc.getOrDefault(docId, 0));
            item.put("chunk_count", chunkCountByDoc.getOrDefault(docId, 0));
            if (matchesKeyword(item, keyword)) {
                allItems.add(item);
            }
        }

        allItems.sort(Comparator.<Map<String, Object>, String>comparing(
                item -> String.valueOf(item.getOrDefault("updated_at", "")),
                Comparator.nullsLast(String::compareTo)
        ).reversed());

        int total = allItems.size();
        int from = Math.min(pageOffset, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> pageItems = allItems.subList(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("limit", pageSize);
        result.put("offset", pageOffset);
        result.put("total", total);
        result.put("items", pageItems);
        result.put("data_file", resolveDataFile().toString());
        return result;
    }

    public Map<String, Object> getDocumentDetail(String docId) {
        if (docId == null || docId.isBlank()) {
            return Map.of("error", "docId 不能为空");
        }

        Map<String, Object> snapshot = loadSnapshot();
        Map<String, Object> docs = asMap(snapshot.get("documents"));
        Map<String, Object> pages = asMap(snapshot.get("pages"));
        Map<String, Object> chunks = asMap(snapshot.get("chunks"));

        Object rawDoc = docs.get(docId);
        if (rawDoc == null) {
            return Map.of("error", "未找到文档: " + docId);
        }

        Map<String, Object> document = new LinkedHashMap<>(asMap(rawDoc));
        document.putIfAbsent("doc_id", docId);

        List<Map<String, Object>> pageList = new ArrayList<>();
        for (Object pageObj : pages.values()) {
            Map<String, Object> page = asMap(pageObj);
            if (docId.equals(String.valueOf(page.get("doc_id")))) {
                pageList.add(new LinkedHashMap<>(page));
            }
        }
        pageList.sort(Comparator.comparingInt(this::extractPageNo));

        int chunkCount = 0;
        for (Object chunkObj : chunks.values()) {
            Map<String, Object> chunk = asMap(chunkObj);
            if (docId.equals(String.valueOf(chunk.get("doc_id")))) {
                chunkCount++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", document);
        result.put("pages", pageList);
        result.put("page_count", pageList.size());
        result.put("chunk_count", chunkCount);
        result.put("data_file", resolveDataFile().toString());
        return result;
    }

    public Map<String, Object> deleteDocument(String docId) {
        if (docId == null || docId.isBlank()) {
            return Map.of("error", "docId 不能为空");
        }

        Map<String, Object> snapshot = loadSnapshot();
        Map<String, Object> docs = asMap(snapshot.get("documents"));
        Map<String, Object> pages = asMap(snapshot.get("pages"));
        Map<String, Object> chunks = asMap(snapshot.get("chunks"));

        if (!docs.containsKey(docId)) {
            return Map.of("error", "未找到文档: " + docId);
        }

        docs.remove(docId);

        List<String> removedPages = new ArrayList<>();
        for (Map.Entry<String, Object> entry : new ArrayList<>(pages.entrySet())) {
            Map<String, Object> page = asMap(entry.getValue());
            if (docId.equals(String.valueOf(page.get("doc_id")))) {
                removedPages.add(entry.getKey());
                pages.remove(entry.getKey());
            }
        }

        int removedChunks = 0;
        for (Map.Entry<String, Object> entry : new ArrayList<>(chunks.entrySet())) {
            Map<String, Object> chunk = asMap(entry.getValue());
            if (docId.equals(String.valueOf(chunk.get("doc_id")))) {
                chunks.remove(entry.getKey());
                removedChunks++;
            }
        }

        snapshot.put("documents", docs);
        snapshot.put("pages", pages);
        snapshot.put("chunks", chunks);
        snapshot.put("updated_at", Instant.now().toString());
        saveSnapshot(snapshot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("deleted_doc_id", docId);
        result.put("deleted_pages", removedPages.size());
        result.put("deleted_chunks", removedChunks);
        return result;
    }

    public Path resolveDataFile() {
        String envDataFile = System.getenv(ENV_DATA_FILE);
        if (hasText(envDataFile)) {
            return resolvePath(envDataFile);
        }
        if (hasText(properties.getDataFile())) {
            return resolvePath(properties.getDataFile());
        }
        String dataDir = System.getenv(ENV_DATA_DIR);
        if (!hasText(dataDir)) {
            dataDir = "data";
        }
        return resolvePath(Path.of(dataDir, DEFAULT_DATA_FILE_NAME).toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSnapshot() {
        Path dataFile = resolveDataFile();
        if (!Files.exists(dataFile)) {
            return emptySnapshot();
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(dataFile.toFile(), new TypeReference<>() {
            });
            snapshot.putIfAbsent("documents", new LinkedHashMap<>());
            snapshot.putIfAbsent("pages", new LinkedHashMap<>());
            snapshot.putIfAbsent("chunks", new LinkedHashMap<>());
            return snapshot;
        } catch (Exception e) {
            log.error("page_index_rag_snapshot_load_failed path={} error={}", dataFile, e.getMessage(), e);
            return emptySnapshot();
        }
    }

    private void saveSnapshot(Map<String, Object> snapshot) {
        Path dataFile = resolveDataFile();
        try {
            Files.createDirectories(dataFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("写入 PageIndexRAG 数据文件失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> emptySnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documents", new LinkedHashMap<>());
        snapshot.put("pages", new LinkedHashMap<>());
        snapshot.put("chunks", new LinkedHashMap<>());
        snapshot.put("updated_at", null);
        return snapshot;
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

    private boolean matchesKeyword(Map<String, Object> item, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        return String.valueOf(item.getOrDefault("doc_id", "")).toLowerCase(Locale.ROOT).contains(kw)
                || String.valueOf(item.getOrDefault("doc_name", "")).toLowerCase(Locale.ROOT).contains(kw)
                || String.valueOf(item.getOrDefault("version", "")).toLowerCase(Locale.ROOT).contains(kw)
                || String.valueOf(item.getOrDefault("metadata", "")).toLowerCase(Locale.ROOT).contains(kw);
    }

    private int extractPageNo(Map<String, Object> page) {
        Object value = page.get("page_no");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignore) {
            return Integer.MAX_VALUE;
        }
    }

    private Path resolvePath(String rawPath) {
        Path path = Path.of(rawPath.trim());
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
