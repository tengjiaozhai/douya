package com.tengjiao.douya.app;


import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public UserVectorApp(VectorStore chromaVectorStore) {
        this.chromaVectorStore = chromaVectorStore;
    }

    /**
     * 存储向量数据（按 userId 隔离）
     *
     * @param documents 要存储的文档列表
     * @param userId 用户ID，用于数据隔离
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

        // 存储到向量数据库
        chromaVectorStore.add(documents);
    }

    /**
     * 相似度搜索（按 userId 隔离）
     *
     * @param query 查询文本（例如："我想吃川菜"）
     * @param userId 用户ID，只搜索该用户的数据
     * @param topK 返回最相似的 K 条结果（默认5条）
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
                ? similarityThreshold : 0.7;

        // 构建搜索请求，使用 userId 过滤实现数据隔离
        SearchRequest searchRequest =
            SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(threshold)
                .filterExpression("userId == '" + userId + "'")
                .build();

        // 执行相似度搜索
        return chromaVectorStore.similaritySearch(searchRequest);
    }

    /**
     * 相似度搜索（使用默认参数）
     *
     * @param query 查询文本
     * @param userId 用户ID
     * @return 最相似的5条结果（相似度阈值0.7）
     */
    public List<Document> searchSimilar(String query, String userId) {
        return searchSimilar(query, userId, 5, 0.0);
    }

    /**
     * 获取所有文档（调试用）
     *
     * @param limit 返回文档的最大数量
     * @param userId 用户ID（可选），如果提供则只返回该用户的文档
     * @return 文档列表
     */
    public List<Document> getAllDocuments(Integer limit, String userId) {
        // 设置默认值
        int k = (limit != null && limit > 0) ? limit : 100;

        // 构建搜索请求
        // 注意：由于 VectorStore 接口基于相似度搜索，我们使用一个通用查询词
        // 并设置较低的相似度阈值来获取更多结果
        SearchRequest.Builder requestBuilder =
            SearchRequest.builder()
                .query("text") // 使用通用查询词而不是空字符串
                .topK(k)
                .similarityThreshold(0.0); // 最低阈值，接受所有结果

        // 如果指定了 userId，添加过滤条件
        if (userId != null && !userId.trim().isEmpty()) {
            requestBuilder.filterExpression("userId == '" + userId + "'");
        }

        SearchRequest searchRequest = requestBuilder.build();

        // 执行搜索
        return chromaVectorStore.similaritySearch(searchRequest);
    }

    /**
     * 搜索所有文档（不使用 userId 过滤，仅用于调试）
     *
     * @param query 查询文本
     * @param topK 返回数量
     * @return 文档列表
     */
    public List<Document> searchWithoutFilter(String query, Integer topK) {
        int k = (topK != null && topK > 0) ? topK : 5;

        SearchRequest searchRequest =
            SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(0.7)
                .build();

        return chromaVectorStore.similaritySearch(searchRequest);
    }
}
