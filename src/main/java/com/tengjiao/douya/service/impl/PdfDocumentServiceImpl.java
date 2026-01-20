package com.tengjiao.douya.service.impl;

import com.tengjiao.douya.model.PdfImageInfo;
import com.tengjiao.douya.model.PdfProcessResult;
import com.tengjiao.douya.service.OssService;
import com.tengjiao.douya.service.PdfDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * PDF 文档处理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfDocumentServiceImpl implements PdfDocumentService {

    private final OssService ossService;
    private final VectorStore chromaVectorStore;

    // 文本切分器配置
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_OVERLAP = 200;

    // 图片格式
    private static final String IMAGE_FORMAT = "png";

    @Override
    @Async
    public CompletableFuture<PdfProcessResult> processPdfDocumentAsync(
            InputStream pdfInputStream,
            String documentName) {
        try {
            PdfProcessResult result = processPdfDocument(pdfInputStream, documentName);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("异步处理 PDF 文档失败: {}", documentName, e);
            return CompletableFuture.completedFuture(
                    PdfProcessResult.builder()
                            .documentName(documentName)
                            .status("FAILED")
                            .errorMessage(e.getMessage())
                            .build());
        }
    }

    @Override
    public PdfProcessResult processPdfDocument(
            InputStream pdfInputStream,
            String documentName) {

        log.info("开始处理 PDF 文档: {}", documentName);

        try {
            // 1. 加载 PDF 文档
            byte[] pdfBytes = pdfInputStream.readAllBytes();
            PDDocument pdDocument = Loader.loadPDF(pdfBytes);

            int totalPages = pdDocument.getNumberOfPages();
            log.info("PDF 总页数: {}", totalPages);

            // 2. 提取图片并上传到 OSS
            List<PdfImageInfo> allImages = extractAndUploadImages(pdDocument, documentName);
            log.info("提取并上传了 {} 张图片", allImages.size());

            // 3. 解析文本内容(按页)
            List<PageContent> pageContents = extractTextByPage(pdDocument);

            // 4. 切分文本并关联图片
            List<Document> documents = splitAndAssociateImages(
                    pageContents,
                    allImages,
                    documentName);

            log.info("生成了 {} 个文档片段", documents.size());

            // 5. 存储到向量数据库
            chromaVectorStore.add(documents);
            log.info("成功存储到向量数据库");

            // 6. 关闭文档
            pdDocument.close();

            return PdfProcessResult.builder()
                    .documentName(documentName)
                    .totalPages(totalPages)
                    .imageCount(allImages.size())
                    .chunkCount(documents.size())
                    .status("SUCCESS")
                    .images(allImages)
                    .build();

        } catch (Exception e) {
            log.error("处理 PDF 文档失败: {}", documentName, e);
            return PdfProcessResult.builder()
                    .documentName(documentName)
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 提取 PDF 中的图片并上传到 OSS
     */
    private List<PdfImageInfo> extractAndUploadImages(PDDocument document, String documentName) {
        List<PdfImageInfo> imageInfos = new ArrayList<>();

        try {
            int pageNum = 0;
            for (PDPage page : document.getPages()) {
                pageNum++;
                int imageIndex = 0;

                // 获取页面资源
                if (page.getResources() == null) {
                    continue;
                }

                // 遍历图片资源
                for (var name : page.getResources().getXObjectNames()) {
                    var xObject = page.getResources().getXObject(name);

                    if (xObject instanceof PDImageXObject imageXObject) {
                        imageIndex++;

                        try {
                            // 转换为 BufferedImage
                            BufferedImage bufferedImage = imageXObject.getImage();

                            // 转换为 PNG 格式
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(bufferedImage, IMAGE_FORMAT, baos);
                            byte[] imageBytes = baos.toByteArray();

                            // 生成 OSS 对象名
                            String fileName = String.format(
                                    "%s_page%d_img%d.%s",
                                    sanitizeFileName(documentName),
                                    pageNum,
                                    imageIndex,
                                    IMAGE_FORMAT);

                            String objectName = String.format(
                                    "documents/%s/%s",
                                    sanitizeFileName(documentName),
                                    fileName);

                            // 上传到 OSS
                            String ossUrl = ossService.uploadFile(
                                    objectName,
                                    new ByteArrayInputStream(imageBytes));

                            // 记录图片信息
                            PdfImageInfo imageInfo = PdfImageInfo.builder()
                                    .fileName(fileName)
                                    .ossUrl(ossUrl)
                                    .pageNumber(pageNum)
                                    .yPosition(0f) // PDFBox 3.x 需要通过其他方式获取位置
                                    .format(IMAGE_FORMAT)
                                    .build();

                            imageInfos.add(imageInfo);
                            log.debug("上传图片: {} -> {}", fileName, ossUrl);

                        } catch (Exception e) {
                            log.warn("提取图片失败: page={}, index={}", pageNum, imageIndex, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("提取图片过程出错", e);
        }

        return imageInfos;
    }

    /**
     * 按页提取文本内容
     */
    private List<PageContent> extractTextByPage(PDDocument document) throws IOException {
        List<PageContent> pageContents = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();

        for (int i = 1; i <= document.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String text = stripper.getText(document);

            pageContents.add(new PageContent(i, text));
        }

        return pageContents;
    }

    /**
     * 切分文本并智能关联图片
     */
    private List<Document> splitAndAssociateImages(
            List<PageContent> pageContents,
            List<PdfImageInfo> allImages,
            String documentName) {

        List<Document> documents = new ArrayList<>();
        TokenTextSplitter splitter = new TokenTextSplitter(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP, 5, 1000, true);

        int globalChunkIndex = 0;

        for (PageContent pageContent : pageContents) {
            int pageNumber = pageContent.pageNumber();
            String pageText = pageContent.text();

            // 获取该页的所有图片
            List<PdfImageInfo> pageImages = allImages.stream()
                    .filter(img -> img.getPageNumber().equals(pageNumber))
                    .collect(Collectors.toList());

            // 切分页面文本
            List<Document> pageChunks = splitter.apply(
                    List.of(new Document(pageText)));

            // 为每个片段添加元数据和关联图片
            for (int i = 0; i < pageChunks.size(); i++) {
                Document chunk = pageChunks.get(i);
                globalChunkIndex++;

                Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                metadata.put("documentName", documentName);
                metadata.put("pageNumber", pageNumber);
                metadata.put("chunkIndex", globalChunkIndex);
                metadata.put("timestamp", Instant.now().toString());

                // 智能关联图片:将该页的图片关联到该页的片段
                // 方案B: 只关联该页的图片到该页的片段
                if (!pageImages.isEmpty()) {
                    List<Map<String, Object>> imageMetadata = pageImages.stream()
                            .map(img -> Map.of(
                                    "fileName", (Object) img.getFileName(),
                                    "ossUrl", img.getOssUrl(),
                                    "position", "page" + img.getPageNumber()))
                            .collect(Collectors.toList());

                    metadata.put("images", imageMetadata);
                }

                Document enrichedDoc = Document.builder()
                        .id(UUID.randomUUID().toString())
                        .text(chunk.getText())
                        .metadata(metadata)
                        .build();

                documents.add(enrichedDoc);
            }
        }

        return documents;
    }

    /**
     * 清理文件名,移除特殊字符
     */
    private String sanitizeFileName(String fileName) {
        // 移除扩展名
        if (fileName.endsWith(".pdf") || fileName.endsWith(".PDF")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }

        // 替换特殊字符
        return fileName.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5-]", "_");
    }

    /**
     * 页面内容记录
     */
    private record PageContent(int pageNumber, String text) {
    }
}
