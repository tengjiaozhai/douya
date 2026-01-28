package com.tengjiao.douya.interfaces.web;

import com.tengjiao.douya.application.service.EatingMasterApp;
import com.tengjiao.douya.domain.eating.model.PdfProcessResult;
import com.tengjiao.douya.domain.eating.service.PdfDocumentService;
import com.tengjiao.douya.infrastructure.config.ChromaProperties;
import com.tengjiao.douya.infrastructure.vectorstore.UserVectorApp;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 吃饭大师接口
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Slf4j
@RestController
@RequestMapping("/douya/eating")
@Tag(name = "吃饭大师接口")
@RequiredArgsConstructor
public class EatingMasterController {

    private final EatingMasterApp eatingMasterApp;
    private final UserVectorApp userVectorApp;
    private final ChromaProperties chromaProperties;
    private final PdfDocumentService pdfDocumentService;

    @GetMapping("/ask")
    @Operation(summary = "询问美食问题")
    public String ask(@RequestParam String question) {
        return eatingMasterApp.ask(question);
    }

    @PostMapping("/pdf/upload")
    @Operation(summary = "上传并处理 PDF 文档(同步)")
    public PdfProcessResult uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentName", required = false) String documentName) {
        
        String fileName = (documentName != null && !documentName.isEmpty()) 
                ? documentName : file.getOriginalFilename();
        
        log.info("接收到 PDF 上传请求: {}, 大小: {} bytes", fileName, file.getSize());

        try {
            return pdfDocumentService.processPdfDocument(file.getInputStream(), fileName);
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", e.getMessage(), e);
            return PdfProcessResult.builder()
                    .documentName(fileName)
                    .status("FAILED")
                    .errorMessage("文件读取失败: " + e.getMessage())
                    .build();
        }
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

    @GetMapping("/vector/status")
    @Operation(summary = "查看向量库状态：统计转换后的向量内容及数量")
    public Map<String, Object> getVectorStatus(
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false) String userId) {

        // 复用获取逻辑，但以更直观的“状态查看”形式呈现
        Map<String, Object> result = getAllDocuments(limit, userId);
        result.put("description", "窥探已向量化的数据片段及元数据信息");
        result.put("tip", "如果 userId 为空，则查询全局（包含系统导入的文档）");
        return result;
    }

    @GetMapping("/vector/collection/raw")
    @Operation(summary = "获取 Collection 的原始向量内容（参考 Chroma get 接口）")
    public Map<String, Object> getCollectionRaw(
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {

        String collectionName = chromaProperties.getCollectionName();
        try {
            return userVectorApp.getChromaRawData(collectionName, limit, offset);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("collectionName", collectionName);
            return error;
        }
    }

    @DeleteMapping("/vector/document")
    @Operation(summary = "根据文档名称物理删除向量数据")
    public Map<String, Object> deleteDocument(@RequestParam String documentName) {
        String collectionName = chromaProperties.getCollectionName();
        try {
            return userVectorApp.deleteByDocumentName(collectionName, documentName);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("status", "FAILED");
            return error;
        }
    }

    @PostMapping("/vector/clean")
    @Operation(summary = "手动触发向量库去重清洗")
    public Map<String, Object> cleanVectors() {
        String collectionName = chromaProperties.getCollectionName();
        try {
            return userVectorApp.cleanupDuplicates(collectionName);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("status", "FAILED");
            return error;
        }
    }
}
