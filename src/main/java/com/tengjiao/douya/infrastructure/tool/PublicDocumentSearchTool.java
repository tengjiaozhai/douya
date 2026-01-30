package com.tengjiao.douya.infrastructure.tool;

import com.tengjiao.douya.infrastructure.vectorstore.UserVectorApp;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.tengjiao.douya.application.service.evaluator.DocumentEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公共文档搜索工具 - 支持 Agentic RAG
 * 用于检索 PDF 文档上传等不区分用户的公共背景知识库。
 * 支持提取文件名、页码及图片 OSS 地址等详细元数据。
 *
 * @author tengjiao
 * @since 2026-01-25
 */
@Slf4j
public class PublicDocumentSearchTool {

    private final UserVectorApp userVectorApp;
    private final DocumentEvaluator evaluator;

    public PublicDocumentSearchTool(UserVectorApp userVectorApp, ChatModel chatModel) {
        this.userVectorApp = userVectorApp;
        this.evaluator = new DocumentEvaluator(chatModel);
    }

    /**
     * 搜索请求
     */
    public record Request(String query) {
    }

    /**
     * 搜索响应
     */
    public record Response(String content) {
    }

    /**
     * 检索系统公共知识、官方手册、菜谱指南等资料
     *
     * @param request 包含查询文本的请求
     * @return 格式化后的检索结果，包含正文、来源信息及图片链接
     */
    public Response search(Request request) {
        String query = request.query();
        log.info("[PublicDocTool] 正在检索公共知识库, Query: {}", query);

        try {
            // 1. 初筛：获取更多候选集 (例如 10 条)
            List<Document> rawDocs = userVectorApp.searchPublic(query, 10);

            if (rawDocs == null || rawDocs.isEmpty()) {
                log.info("[PublicDocTool] 未找到与 '{}' 相关的公共文档内容", query);
                return new Response("未在公共知识库中找到相关信息。");
            }

            // 2. 评估与重排：取 Top 3
            List<Document> docs = evaluator.evaluateAndRerank(query, rawDocs, 3);

            if (docs.isEmpty()) {
                return new Response("虽然初筛有结果，但经评估与您的问题关联度不高。");
            }

            // 格式化输出，带上详细元数据
            String formattedResult = docs.stream().map(doc -> {
                Map<String, Object> meta = doc.getMetadata();
                String docName = (String) meta.getOrDefault("documentName", "未知文档");
                Object pageNum = meta.getOrDefault("pageNumber", "未知页码");

                StringBuilder sb = new StringBuilder();
                sb.append("【来源: ").append(docName).append(" (第 ").append(pageNum).append(" 页)】\n");
                sb.append("内容摘要: ").append(doc.getText()).append("\n");

                // 处理图片元数据
                Object imagesObj = meta.get("images");
                if (imagesObj instanceof String jsonStr && !jsonStr.isBlank()) {
                    try {
                        JSONArray jsonArray = JSON.parseArray(jsonStr);
                        if (jsonArray != null && !jsonArray.isEmpty()) {
                            sb.append("关联图片:\n");
                            for (int i = 0; i < jsonArray.size(); i++) {
                                JSONObject img = jsonArray.getJSONObject(i);
                                String ossUrl = img.getString("ossUrl");
                                // 使用显式标签格式,便于 LLM 识别和保留
                                sb.append("[图片资产]: ossUrl=").append(ossUrl).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析图片元数据失败: {}", jsonStr);
                    }
                }
                return sb.toString();
            }).collect(Collectors.joining("\n---\n"));

            log.info("[PublicDocTool] 检索到 {} 条公共记录", docs.size());
            return new Response("根据公共系统手册找到以下内容：\n\n" + formattedResult);

        } catch (Exception e) {
            log.error("[PublicDocTool] 检索失败", e);
            return new Response("检索公共知识库时发生错误。");
        }
    }
}
