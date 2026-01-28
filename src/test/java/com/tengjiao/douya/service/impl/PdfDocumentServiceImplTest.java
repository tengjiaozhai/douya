package com.tengjiao.douya.service.impl;

import com.tengjiao.douya.domain.eating.model.PdfProcessResult;
import com.tengjiao.douya.domain.eating.service.PdfDocumentService;


import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PDF 文档处理服务测试
 */
@Slf4j
@SpringBootTest
class PdfDocumentServiceImplTest {

    @Autowired
    private PdfDocumentService pdfDocumentService;

    @Test
    void testProcessPdfDocument() throws Exception {
        // 加载测试 PDF 文件
        ClassPathResource resource = new ClassPathResource("document/document.pdf");

        try (InputStream inputStream = resource.getInputStream()) {
            // 处理 PDF 文档
            PdfProcessResult result = pdfDocumentService.processPdfDocument(
                    inputStream,
                    "测试文档.pdf");

            // 验证结果
            assertNotNull(result);
            assertEquals("SUCCESS", result.getStatus());
            assertNotNull(result.getTotalPages());
            assertNotNull(result.getImageCount());
            assertNotNull(result.getChunkCount());

            log.info("处理结果: {}", result);
            log.info("总页数: {}", result.getTotalPages());
            log.info("提取图片数: {}", result.getImageCount());
            log.info("文档片段数: {}", result.getChunkCount());

            // 验证图片信息
            if (result.getImageCount() > 0) {
                assertNotNull(result.getImages());
                assertFalse(result.getImages().isEmpty());

                result.getImages().forEach(img -> {
                    log.info("图片: {} -> {}", img.getFileName(), img.getOssUrl());
                    assertNotNull(img.getOssUrl());
                    assertTrue(img.getOssUrl().startsWith("http"));
                });
            }
        }
    }

    @Test
    void testProcessPdfDocumentAsync() throws Exception {
        // 加载测试 PDF 文件
        ClassPathResource resource = new ClassPathResource("document/document.pdf");

        try (InputStream inputStream = resource.getInputStream()) {
            // 异步处理 PDF 文档
            var future = pdfDocumentService.processPdfDocumentAsync(
                    inputStream,
                    "异步测试文档.pdf");

            // 等待完成
            PdfProcessResult result = future.get();

            // 验证结果
            assertNotNull(result);
            assertEquals("SUCCESS", result.getStatus());

            log.info("异步处理完成: {}", result);
        }
    }
}
