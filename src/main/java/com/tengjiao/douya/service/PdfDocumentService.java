package com.tengjiao.douya.service;

import com.tengjiao.douya.model.PdfProcessResult;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/**
 * PDF 文档处理服务接口
 */
public interface PdfDocumentService {

    /**
     * 异步处理 PDF 文档:解析、切分、向量化
     * 
     * @param pdfInputStream PDF 文件输入流
     * @param documentName   文档名称
     * @return 处理结果的 Future
     */
    CompletableFuture<PdfProcessResult> processPdfDocumentAsync(
            InputStream pdfInputStream,
            String documentName);

    /**
     * 同步处理 PDF 文档(用于小文档)
     * 
     * @param pdfInputStream PDF 文件输入流
     * @param documentName   文档名称
     * @return 处理结果
     */
    PdfProcessResult processPdfDocument(
            InputStream pdfInputStream,
            String documentName);
}
