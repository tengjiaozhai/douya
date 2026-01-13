package com.tengjiao.douya.infra.tool;

import com.tengjiao.douya.app.UserVectorApp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆搜索工具 - 支持 Agentic RAG
 * 允许智能体根据需要主动检索用户的历史背景或本地知识库
 *
 * @author tengjiao
 * @since 2026-01-13
 */
@Slf4j
public class MemorySearchTool {

    private final UserVectorApp userVectorApp;
    private final String userId;

    public MemorySearchTool(UserVectorApp userVectorApp, String userId) {
        this.userVectorApp = userVectorApp;
        this.userId = userId;
    }

    /**
     * 搜索本地记忆请求
     */
    public record Request(String query) {
    }

    /**
     * 搜索本地记忆响应
     */
    public record Response(String content) {
    }

    /**
     * 搜索本地历史记忆或背景知识
     *
     * @param request 包含查询文本的请求
     * @return 检索到的聚合内容
     */
    public Response search(Request request) {
        String query = request.query();
        log.info("[MemoryTool] 用户 {} 正在检索记忆, Query: {}", userId, query);

        try {
            // 使用 UserVectorApp 进行相似度搜索
            // 阈值设置为 0.5 以保证一定的相关性
            List<Document> docs = userVectorApp.searchSimilar(query, userId, 3, 0.5);

            if (docs == null || docs.isEmpty()) {
                log.info("[MemoryTool] 未找到与 '{}' 相关的本地记忆", query);
                return new Response("未在本地记忆库中找到与您的查询相关的具体历史记录或背景知识。");
            }

            String combinedContent = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));

            log.info("[MemoryTool] 检索到 {} 条相关记录", docs.size());
            return new Response("找到以下相关历史记录/背景知识：\n" + combinedContent);

        } catch (Exception e) {
            log.error("[MemoryTool] 检索失败", e);
            return new Response("检索本地记忆时发生错误，请尝试其他方式。");
        }
    }
}
