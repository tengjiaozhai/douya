package com.tengjiao.douya.controller;

import com.tengjiao.douya.app.EatingMasterApp;
import com.tengjiao.douya.app.UserVectorApp;
import com.tengjiao.douya.config.ChromaProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 吃饭大师接口
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@RestController
@RequestMapping("/douya/eating")
@Tag(name = "吃饭大师接口")
@RequiredArgsConstructor
public class EatingMasterController {

    private final EatingMasterApp eatingMasterApp;
    private final UserVectorApp userVectorApp;
    private final ChromaProperties chromaProperties;

    @GetMapping("/ask")
    @Operation(summary = "询问美食问题")
    public String ask(@RequestParam String question) {
        return eatingMasterApp.ask(question);
    }

    @GetMapping("/documents")
    @Operation(summary = "查询 collection 下的所有文档（调试用）")
    public Map<String, Object> getAllDocuments(
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false) String userId) {

        String collectionName = chromaProperties.getCollectionName();

        try {
            // 调用 service 获取文档
            java.util.List<Document> documents =
                userVectorApp.getAllDocuments(limit, userId);

            // 构建返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("collectionName", collectionName);
            result.put("totalDocuments", documents.size());
            result.put("documents", documents.stream().map(doc -> {
                Map<String, Object> docInfo = new HashMap<>();
                docInfo.put("id", doc.getId());
                docInfo.put("content", doc.getText());
                docInfo.put("metadata", doc.getMetadata());
                return docInfo;
            }).toList());

            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("collectionName", collectionName);
            error.put("stackTrace", e.getClass().getName());
            return error;
        }
    }
}
