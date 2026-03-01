package com.tengjiao.douya.domain.eating.service;

import com.tengjiao.douya.domain.eating.model.PdfProcessResult;
import com.tengjiao.douya.domain.eating.model.PdfSplitOptions;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/**
 * PDF 文档处理服务接口
 */
public interface PdfDocumentService {

    /**
     * 异步处理 PDF 文档:解析、切分、向量化
     */
    CompletableFuture<PdfProcessResult> processPdfDocumentAsync(
            InputStream pdfInputStream,
            String documentName);

    /**
     * 异步处理 PDF 文档:解析、切分、向量化（可指定切分策略）
     */
    CompletableFuture<PdfProcessResult> processPdfDocumentAsync(
            InputStream pdfInputStream,
            String documentName,
            PdfSplitOptions options);

    /**
     * 同步处理 PDF 文档(用于小文档)
     */
    PdfProcessResult processPdfDocument(
            InputStream pdfInputStream,
            String documentName);

    /**
     * 同步处理 PDF 文档(用于小文档，可指定切分策略)
     */
    PdfProcessResult processPdfDocument(
            InputStream pdfInputStream,
            String documentName,
            PdfSplitOptions options);
}
