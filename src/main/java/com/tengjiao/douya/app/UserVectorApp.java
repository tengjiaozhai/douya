package com.tengjiao.douya.app;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户向量服务 - 吃饭大师场景
 * 提供基于用户隔离的向量存储和相似度搜索功能
 *
 * @author tengjiao
 * @since 2025-12-10 15:59
 */
@Component
public class UserVectorApp {
    private final VectorStore chromaVectorStore;
    private final ChromaApi chromaApi;

    public UserVectorApp(VectorStore chromaVectorStore, ChromaApi chromaApi) {
        this.chromaVectorStore = chromaVectorStore;
        this.chromaApi = chromaApi;
    }

    /**
     * 存储向量数据（按 userId 隔离）
     *
     * @param documents 要存储的文档列表
     * @param userId    用户ID，用于数据隔离
     */
    public void addDocuments(List<Document> documents, String userId) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("文档列表不能为空");
        }
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        // 为每个文档添加 userId 元数据，实现数据隔离
        documents.forEach(doc -> {
            doc.getMetadata().put("userId", userId);
            // 添加时间戳，便于后续管理
            doc.getMetadata().put("timestamp", System.currentTimeMillis());
        });

        // 存储到向量数据库 (分批存储，解决 DashScope 单次请求限制，目前限制为 10 条)
        int batchSize = 10;
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);
            chromaVectorStore.add(batch);
        }
    }

    /**
     * 相似度搜索（按 userId 隔离）
     *
     * @param query               查询文本（例如："我想吃川菜"）
     * @param userId              用户ID，只搜索该用户的数据
     * @param topK                返回最相似的 K 条结果（默认5条）
     * @param similarityThreshold 相似度阈值（0-1之间，默认0.7）
     * @return 相似文档列表
     */
    public List<Document> searchSimilar(
            String query,
            String userId,
            Integer topK,
            Double similarityThreshold) {

        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("查询文本不能为空");
        }
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        // 设置默认值
        int k = (topK != null && topK > 0) ? topK : 5;
        double threshold = (similarityThreshold != null && similarityThreshold >= 0 && similarityThreshold <= 1)
                ? similarityThreshold
                : 0.7;

        // 构建搜索请求，使用 userId 过滤实现数据隔离
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(threshold)
                .filterExpression("userId == '" + userId + "'")
                .build();

        // 执行相似度搜索
        List<Document> docs = chromaVectorStore.similaritySearch(searchRequest);

        // 应用 Parent-Child 策略：替换为父块文本并去重
        return applyParentContext(docs);
    }

    /**
     * 相似度搜索（使用默认参数）
     *
     * @param query  查询文本
     * @param userId 用户ID
     * @return 最相似的5条结果（相似度阈值0.7）
     */
    public List<Document> searchSimilar(String query, String userId) {
        return searchSimilar(query, userId, 5, 0.0);
    }

    /**
     * 获取所有文档（调试用）
     *
     * @param limit  返回文档的最大数量
     * @param userId 用户ID（可选），如果提供则只返回该用户的文档
     * @return 文档列表
     */
    public List<Document> getAllDocuments(Integer limit, String userId) {
        // 设置默认值
        int k = (limit != null && limit > 0) ? limit : 100;

        // 构建搜索请求
        // 注意：由于 VectorStore 接口基于相似度搜索，我们使用一个通用查询词
        // 并设置较低的相似度阈值来获取更多结果
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query("text") // 使用通用查询词而不是空字符串
                .topK(k)
                .similarityThreshold(0.0); // 最低阈值，接受所有结果

        // 如果指定了 userId，添加过滤条件
        if (userId != null && !userId.trim().isEmpty()) {
            requestBuilder.filterExpression("userId == '" + userId + "'");
        }

        SearchRequest searchRequest = requestBuilder.build();

        // 执行搜索
        List<Document> docs = chromaVectorStore.similaritySearch(searchRequest);
        return applyParentContext(docs);
    }

    /**
     * 搜索公共文档（不带 userId 过滤）
     * 适用于检索 PDF 文档上传等系统级公共知识
     *
     * @param query 查询文本
     * @param topK  数量
     * @return 包含元数据封装好的文档列表
     */
    public List<Document> searchPublic(String query, Integer topK) {
        int k = (topK != null && topK > 0) ? topK : 5;

        // 构建搜索请求：不指定 filterExpression 默认检索全库
        // 如果后续需要明确区分，可以在上传时添加 public=true 并在此时过滤
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(0.5) // 设置适中的阈值确保质量
                .build();

        List<Document> docs = chromaVectorStore.similaritySearch(searchRequest);

        // 排除掉带有 userId 的文档，只保留公共文档
        List<Document> publicDocs = docs.stream()
                .filter(doc -> !doc.getMetadata().containsKey("userId"))
                .toList();

        return applyParentContext(publicDocs);
    }

    /**
     * 搜索所有文档（不使用 userId 过滤，仅用于调试）
     *
     * @param query 查询文本
     * @param topK  返回数量
     * @return 文档列表
     */
    public List<Document> searchWithoutFilter(String query, Integer topK) {
        int k = (topK != null && topK > 0) ? topK : 5;

        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(0.7)
                .build();

        List<Document> docs = chromaVectorStore.similaritySearch(searchRequest);
        return applyParentContext(docs);
    }

    /**
     * 应用 Parent-Child 扩展策略并去重
     * 保持原始排序（基于 LinkedHashMap）
     */
    private List<Document> applyParentContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }

        Map<String, Document> processedMap = new LinkedHashMap<>();

        for (Document doc : docs) {
            String parentText = (String) doc.getMetadata().get("parent_text");
            String contentToUse = (parentText != null && !parentText.trim().isEmpty())
                    ? parentText
                    : doc.getText();

            // 如果该父块（或同内容片段）尚未加入，则加入
            // 这样可以确保如果多个子块命中同一个父块，只向 LLM 提供一次完整的父块上下文
            if (!processedMap.containsKey(contentToUse)) {
                Document processedDoc = Document.builder()
                        .id(doc.getId())
                        .text(contentToUse)
                        .metadata(doc.getMetadata())
                        .score(doc.getScore())
                        .build();
                processedMap.put(contentToUse, processedDoc);
            }
        }

        return new ArrayList<>(processedMap.values());
    }

    /**
     * 直接通过 ChromaApi 获取原始结构数据
     * 参考底层结构，返回包含 ids, documents, metadatas 的原始 Map
     */
    public Map<String, Object> getChromaRawData(String collectionName, Integer limit) {
        int k = (limit != null && limit > 0) ? limit : 100;
        String tenant = "SpringAiTenant";
        String database = "SpringAiDatabase";

        // 1. 先获取 collection 以获得 ID
        ChromaApi.Collection collection = chromaApi.getCollection(tenant, database, collectionName);
        if (collection == null) {
            return Map.of("error", "Collection not found: " + collectionName);
        }

        // 2. 构建原始 API 请求
        // 源码中 GetEmbeddingsRequest 的构造函数之一支持 (ids, where, limit, offset, include)
        ChromaApi.GetEmbeddingsRequest getRequest = new ChromaApi.GetEmbeddingsRequest(
                null, // ids
                null, // where clause
                k, // limit
                0, // offset
                List.of(ChromaApi.QueryRequest.Include.DOCUMENTS,
                        ChromaApi.QueryRequest.Include.METADATAS,
                        ChromaApi.QueryRequest.Include.EMBEDDINGS));

        // 3. 调用底层 API
        ChromaApi.GetEmbeddingResponse response = chromaApi.getEmbeddings(tenant, database, collection.id(),
                getRequest);

        // 4. 组装结果，尽可能贴合用户展示的格式
        Map<String, Object> result = new HashMap<>();
        if (response != null) {
            result.put("ids", response.ids());
            result.put("documents", response.documents());
            result.put("metadatas", response.metadata());
            result.put("embeddings", response.embeddings());
        }
        return result;
    }

    /**
     * 清理 Collection 中的重复数据
     * 策略：以 text 内容完全一致作为重复判定标准，仅保留最早的一条
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cleanupDuplicates(String collectionName) {
        String tenant = "SpringAiTenant";
        String database = "SpringAiDatabase";

        // 1. 获取 Collection 信息
        ChromaApi.Collection collection = chromaApi.getCollection(tenant, database, collectionName);
        if (collection == null) {
            return Map.of("error", "Collection not found: " + collectionName);
        }

        // 2. 获取全量数据进行扫描 (此处 limit 设置较大以覆盖现有测试数据)
        Map<String, Object> rawData = getChromaRawData(collectionName, 5000);
        List<String> ids = (List<String>) rawData.get("ids");
        List<String> documents = (List<String>) rawData.get("documents");

        if (ids == null || documents == null || ids.isEmpty()) {
            return Map.of("message", "No data to clean", "totalCount", 0);
        }

        // 3. 内容哈希去重逻辑
        Map<String, String> contentMap = new LinkedHashMap<>(); // content -> firstEncounteredId
        List<String> idsToDelete = new ArrayList<>();
        int totalScan = ids.size();

        for (int i = 0; i < totalScan; i++) {
            String id = ids.get(i);
            String content = documents.get(i);

            if (contentMap.containsKey(content)) {
                // 重复出现，记录 ID 准备删除
                idsToDelete.add(id);
            } else {
                // 第一次出现，保留
                contentMap.put(content, id);
            }
        }

        // 4. 执行物理删除
        int deleteApiStatus = 0;
        if (!idsToDelete.isEmpty()) {
            ChromaApi.DeleteEmbeddingsRequest deleteRequest = new ChromaApi.DeleteEmbeddingsRequest(idsToDelete);
            deleteApiStatus = chromaApi.deleteEmbeddings(tenant, database, collection.id(), deleteRequest);
        }

        // 5. 生成报告
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("collection", collectionName);
        report.put("totalScan", totalScan);
        report.put("uniqueCount", contentMap.size());
        report.put("duplicateCount", idsToDelete.size());
        report.put("deleteApiStatus", deleteApiStatus);
        report.put("message", idsToDelete.isEmpty() ? "No duplicates found." : "Cleanup completed successfully.");
        return report;
    }

}
