package com.tengjiao.douya.service.impl;

import com.tengjiao.douya.model.PdfImageInfo;
import com.tengjiao.douya.model.PdfProcessResult;
import com.tengjiao.douya.service.OssService;
import com.tengjiao.douya.service.PdfDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

    // 文本切分器配置 - Parent-Child 策略
    private static final int PARENT_CHUNK_SIZE = 1200;
    private static final int PARENT_CHUNK_OVERLAP = 200;
    private static final int CHILD_CHUNK_SIZE = 200;
    private static final int CHILD_CHUNK_OVERLAP = 50;

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
            List<PageContent> rawPageContents = extractTextByPage(pdDocument);

            // 4. 数据清洗：剔除页眉页脚、乱码等
            List<PageContent> pageContents = cleanPageContents(rawPageContents);

            // 5. 切分文本(Parent-Child 策略)并关联图片
            List<Document> documents = splitParentChildAndAssociate(
                    pageContents,
                    allImages,
                    documentName);

            log.info("生成了 {} 个文档片段", documents.size());

            // 5. 存储到向量数据库 (分批存储，解决 DashScope 单次请求限制，目前限制为 10 条)
            int batchSize = 10;
            for (int i = 0; i < documents.size(); i += batchSize) {
                int end = Math.min(i + batchSize, documents.size());
                List<Document> batch = documents.subList(i, end);
                chromaVectorStore.add(batch);
                log.info("已存储向量分块: {} - {} / {}", i, end, documents.size());
            }
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

    private List<PdfImageInfo> extractAndUploadImages(PDDocument document, String documentName) {
        // 使用线程安全的列表存储结果
        List<PdfImageInfo> imageInfos = Collections.synchronizedList(new ArrayList<>());

        // 用于存储正在进行或已完成的上传任务，避免同一张图片重复处理
        Map<COSStream, CompletableFuture<ImageUploadResult>> uploadTasks = new ConcurrentHashMap<>();

        // 全局唯一图片计数器
        AtomicInteger uniqueImageCounter = new AtomicInteger(0);

        // 收集所有页面的图片关联任务
        List<CompletableFuture<Void>> pageImageTasks = new ArrayList<>();

        try {
            int pageNum = 0;

            for (PDPage page : document.getPages()) {
                pageNum++;
                final int currentPageNum = pageNum;

                // 获取页面资源
                if (page.getResources() == null) {
                    continue;
                }

                // 遍历图片资源
                for (var name : page.getResources().getXObjectNames()) {
                    var xObject = page.getResources().getXObject(name);

                    if (xObject instanceof PDImageXObject imageXObject) {
                        COSStream cosStream = imageXObject.getCOSObject();

                        // 1. 获取 BufferedImage (PDFBox 解析过程建议在主线程顺序执行以确保线程安全)
                        BufferedImage bufferedImage = imageXObject.getImage();

                        // 2. 提交并发任务处理编码与上传 (慢速操作并行化)
                        CompletableFuture<ImageUploadResult> uploadTask = uploadTasks.computeIfAbsent(cosStream,
                                stream -> {
                                    return CompletableFuture.supplyAsync(() -> {
                                        try {
                                            int imgId = uniqueImageCounter.incrementAndGet();

                                            // 转换为 PNG 格式 (CPU 密集)
                                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                            ImageIO.write(bufferedImage, IMAGE_FORMAT, baos);
                                            byte[] imageBytes = baos.toByteArray();

                                            // 生成文件名与路径
                                            String fileName = String.format(
                                                    "%s_img%d.%s",
                                                    sanitizeFileName(documentName),
                                                    imgId,
                                                    IMAGE_FORMAT);

                                            String objectName = String.format(
                                                    "documents/%s/%s",
                                                    sanitizeFileName(documentName),
                                                    fileName);

                                            // 上传到 OSS (I/O 密集)
                                            String ossUrl;
                                            if (!ossService.doesObjectExist(objectName)) {
                                                ossUrl = ossService.uploadFile(
                                                        objectName,
                                                        new ByteArrayInputStream(imageBytes));
                                                log.info("上传新图片: {} -> {}", fileName, ossUrl);
                                            } else {
                                                ossUrl = ossService.getFileUrl(objectName);
                                                log.info("复用 OSS 已有图片: {} -> {}", fileName, ossUrl);
                                            }

                                            return new ImageUploadResult(ossUrl, fileName);
                                        } catch (Exception e) {
                                            log.error("图片上传任务执行失败", e);
                                            throw new RuntimeException("图片上传失败", e);
                                        }
                                    });
                                });

                        // 3. 当上传完成后，将记录添加到当前页面的信息列表中
                        pageImageTasks.add(uploadTask.thenAccept(result -> {
                            PdfImageInfo imageInfo = PdfImageInfo.builder()
                                    .fileName(result.fileName())
                                    .ossUrl(result.ossUrl())
                                    .pageNumber(currentPageNum)
                                    .yPosition(0f)
                                    .format(IMAGE_FORMAT)
                                    .build();
                            imageInfos.add(imageInfo);
                        }));
                    }
                }
            }

            // 等待所有图片处理完成
            CompletableFuture.allOf(pageImageTasks.toArray(new CompletableFuture[0])).join();

        } catch (Exception e) {
            log.error("提取图片过程出错", e);
        }

        // 最终结果按照页码排序
        return imageInfos.stream()
                .sorted(Comparator.comparingInt(PdfImageInfo::getPageNumber))
                .collect(Collectors.toList());
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
     * 数据清洗：识别页眉页脚并剔除乱码
     */
    private List<PageContent> cleanPageContents(List<PageContent> pageContents) {
        if (pageContents.isEmpty())
            return pageContents;

        // 1. 简单的页眉页脚识别：统计首尾行出现频率
        Map<String, Integer> headerFooterCounts = new HashMap<>();
        for (PageContent pc : pageContents) {
            String[] lines = pc.text().split("\\r?\\n");
            if (lines.length > 0) {
                String firstLine = lines[0].trim();
                if (!firstLine.isEmpty())
                    headerFooterCounts.merge(firstLine, 1, (a, b) -> a + b);

                String lastLine = lines[lines.length - 1].trim();
                if (!lastLine.isEmpty())
                    headerFooterCounts.merge(lastLine, 1, (a, b) -> a + b);
            }
        }

        // 认为出现次数超过总页数一半的行是页眉页脚
        Set<String> noiseLines = headerFooterCounts.entrySet().stream()
                .filter(e -> e.getValue() > pageContents.size() * 0.5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        return pageContents.stream().map(pc -> {
            String text = pc.text();
            for (String noise : noiseLines) {
                text = text.replace(noise, "");
            }

            // 2. 正则清洗乱码、控制字符等
            // 移除 ASCII 控制字符
            text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
            // 移除连续的空白字符
            text = text.replaceAll("\\s{2,}", " ");

            return new PageContent(pc.pageNumber(), text.trim());
        }).collect(Collectors.toList());
    }

    /**
     * 采用 Parent-Child (小到大) 检索策略切分文本并管理图片
     */
    private List<Document> splitParentChildAndAssociate(
            List<PageContent> pageContents,
            List<PdfImageInfo> allImages,
            String documentName) {

        List<Document> childDocuments = new ArrayList<>();
        TokenTextSplitter parentSplitter = new TokenTextSplitter(PARENT_CHUNK_SIZE, PARENT_CHUNK_OVERLAP, 5, 1000,
                true);
        TokenTextSplitter childSplitter = new TokenTextSplitter(CHILD_CHUNK_SIZE, CHILD_CHUNK_OVERLAP, 5, 1000, true);

        int globalChildIndex = 0;

        // 预分组图片，提高检索效率
        Map<Integer, List<PdfImageInfo>> imagesByPage = allImages.stream()
                .collect(Collectors.groupingBy(PdfImageInfo::getPageNumber));

        for (PageContent pageContent : pageContents) {
            int pageNumber = pageContent.pageNumber();
            String pageText = pageContent.text();

            if (pageText.isEmpty())
                continue;

            // 获取该页的所有图片
            List<PdfImageInfo> pageImages = imagesByPage.getOrDefault(pageNumber, Collections.emptyList());

            List<Map<String, Object>> imageMetadataList = pageImages.stream()
                    .map(img -> Map.of(
                            "fileName", (Object) img.getFileName(),
                            "ossUrl", img.getOssUrl(),
                            "position", "page" + img.getPageNumber()))
                    .collect(Collectors.toList());

            // 1. 先切分为 Parent 块
            List<Document> parents = parentSplitter.apply(List.of(new Document(pageText)));

            for (Document parentChunk : parents) {
                // 2. 在 Parent 内部切分为更精确的 Child 块
                List<Document> children = childSplitter.apply(List.of(parentChunk));

                for (Document childChunk : children) {
                    globalChildIndex++;

                    Map<String, Object> metadata = new HashMap<>(childChunk.getMetadata());
                    metadata.put("documentName", documentName);
                    metadata.put("pageNumber", pageNumber);
                    metadata.put("childIndex", globalChildIndex);
                    metadata.put("timestamp", Instant.now().toString());

                    // 核心逻辑：关联父块文本
                    metadata.put("parent_text", parentChunk.getText());
                    metadata.put("is_child", true);

                    // 关联该页图片
                    if (!imageMetadataList.isEmpty()) {
                        metadata.put("images", imageMetadataList);
                    }

                    Document enrichedChild = Document.builder()
                            .id(UUID.randomUUID().toString())
                            .text(childChunk.getText())
                            .metadata(metadata)
                            .build();

                    childDocuments.add(enrichedChild);
                }
            }
        }

        return childDocuments;
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
     * 图片上传结果记录
     */
    private record ImageUploadResult(String ossUrl, String fileName) {
    }

    /**
     * 页面内容记录
     */
    private record PageContent(int pageNumber, String text) {
    }
}
