package com.tengjiao.douya.domain.eating.service;

import com.tengjiao.douya.domain.eating.model.PdfImageInfo;
import com.tengjiao.douya.domain.eating.model.PdfProcessResult;
import com.tengjiao.douya.infrastructure.oss.OssService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

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

    // 文本切分器配置 - 语义化 Parent-Child 策略
    private static final int PARENT_CONTEXT_SIZE = 500; // 侧向扩展的上下文
    private static final int CHILD_CHUNK_SIZE = 300; // 子块大小
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

            // 4. 新流程：Filter -> Merge -> Split -> Clean

            // 4.1 初步去噪 (仅移除页眉页脚)
            List<PageContent> filteredContents = filterNoise(rawPageContents);

            // 4.2 段落重组 (修复断句)
            List<PageContent> mergedContents = mergeParagraphs(filteredContents);

            // 4.3 切分文本 (Parent-Child 策略) 并关联图片
            List<Document> rawDocuments = splitParentChildAndAssociate(
                    mergedContents,
                    allImages,
                    documentName);

            // 4.4 最终清洗 (对切片后的文本进行规范化)
            List<Document> documents = cleanChunks(rawDocuments);

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
        stripper.setSortByPosition(true); // 开启位置排序，处理多栏布局更准确

        for (int i = 1; i <= document.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String text = stripper.getText(document);

            // 保持页面的原始性，但在每一页末尾确保有换行
            if (text != null && !text.endsWith("\n")) {
                text += "\n";
            }
            pageContents.add(new PageContent(i, text));
        }

        return pageContents;
    }

    /**
     * 2. 数据清洗第一步：仅剔除明显的页眉页脚噪声，保留原始结构
     */
    private List<PageContent> filterNoise(List<PageContent> pageContents) {
        if (pageContents.isEmpty())
            return pageContents;

        // 统计行频次，识别页眉页脚
        Map<String, Integer> lineCounts = new HashMap<>();
        for (PageContent pc : pageContents) {
            String[] lines = pc.text().split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                // 过滤掉太短的行（如页码）但注意不要误伤正文短句
                if (trimmed.length() > 3) {
                    lineCounts.merge(trimmed, 1, Integer::sum);
                }
            }
        }

        // 出现频率极高（如超过 20% 的页面都有）且非正文内容的行
        // 降低阈值到 20% 以更积极地去除页眉
        Set<String> noiseLines = lineCounts.entrySet().stream()
                .filter(e -> e.getValue() > pageContents.size() * 0.2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // 增加特定的装饰性噪声关键词
        Set<String> explicitNoise = Set.of("Our Menu", "Recipe", "Ingredients", "Method", "主食谱", "配料", "做法");

        return pageContents.stream().map(pc -> {
            String text = pc.text();
            String[] lines = text.split("\\r?\\n");
            StringBuilder cleanedText = new StringBuilder();

            for (String line : lines) {
                String trimmed = line.trim();

                // 1. 移除高频噪声行 (页眉页脚)
                if (noiseLines.contains(trimmed)) {
                    continue;
                }

                // 1.1 移除显式噪声关键词 (仅当行内容完全匹配或者是极短的装饰词)
                if (explicitNoise.contains(trimmed)
                        || (trimmed.length() < 20 && explicitNoise.stream().anyMatch(trimmed::contains))) {
                    continue;
                }

                // 2. 移除孤立的页码
                if (trimmed.matches("^\\s*第\\s*\\d+\\s*页.*$") ||
                        trimmed.matches("^\\s*-\\s*\\d+\\s*-\\s*$") ||
                        trimmed.matches("^\\s*\\d+\\s*/\\s*\\d+\\s*$") ||
                        trimmed.matches("^\\s*\\d+\\s*$")) {
                    continue;
                }

                cleanedText.append(line).append("\n");
            }
            return new PageContent(pc.pageNumber(), cleanedText.toString());
        }).collect(Collectors.toList());
    }

    /**
     * 3. 结构重组：合并被硬换行打断的段落
     * 优化：增加了对目录、列表项、短语标题的保护，防止错误合并
     */
    private List<PageContent> mergeParagraphs(List<PageContent> pageContents) {
        return pageContents.stream().map(pc -> {
            String text = pc.text();
            String[] lines = text.split("\\n");
            StringBuilder mergedText = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    // 保留空行作为段落分隔符 (将连续空行归一化为双换行)
                    if (mergedText.length() > 0 && mergedText.charAt(mergedText.length() - 1) != '\n') {
                        mergedText.append("\n\n");
                    }
                    continue;
                }

                mergedText.append(line);

                // 瞻前：获取下一行内容以辅助判断
                String nextLine = (i + 1 < lines.length) ? lines[i + 1].trim() : "";

                // 核心逻辑：判断是否需要合并下一行
                if (shouldMerge(line, nextLine)) {
                    mergedText.append(" ");
                } else {
                    mergedText.append("\n");
                }
            }
            // 确保页面最后有换行
            if (mergedText.length() > 0 && mergedText.charAt(mergedText.length() - 1) != '\n') {
                mergedText.append("\n");
            }

            return new PageContent(pc.pageNumber(), mergedText.toString());
        }).collect(Collectors.toList());
    }

    /**
     * 判断是否应该合并下一行
     * 只有当当前行看起来像是句子中间断开，且下一行不是新的列表项时，才合并
     */
    private boolean shouldMerge(String line, String nextLine) {
        // 1. 如果当前行已经是句末，不合并
        if (isSentenceEnd(line))
            return false;

        // 2. 目录保护：如果行尾是数字 (Page xx)，视为目录项，不合并
        if (isTableOfContentsItem(line))
            return false;

        // 3. 列表/标题保护：如果当前行太短 (<20字符)，视为标签/标题/配料，不合并
        if (isShortLine(line))
            return false;

        // 4. 下一行列表保护：如果下一行看起来像是列表项 (1. / - / •)，不合并
        if (isListItem(nextLine))
            return false;

        return true;
    }

    private boolean isSentenceEnd(String line) {
        if (line.isEmpty())
            return false;
        char lastChar = line.charAt(line.length() - 1);
        return lastChar == '。' || lastChar == '！' || lastChar == '？' ||
                lastChar == '.' || lastChar == '!' || lastChar == '?';
    }

    private boolean isTableOfContentsItem(String line) {
        return line.matches(".*\\s+\\d+$");
    }

    private boolean isListItem(String line) {
        return line.matches("^(\\d+\\.|[a-zA-Z]\\.|-|•|\\*|\\d+).*");
    }

    private boolean isShortLine(String line) {
        return line.length() < 20;
    }

    /**
     * 5. 切分后清洗：对最终生成的 Document 进行细粒度清洗
     */
    private List<Document> cleanChunks(List<Document> documents) {
        return documents.stream().map(doc -> {
            String cleanedText = deepClean(doc.getText());

            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            if (metadata.containsKey("parent_text")) {
                String parentText = (String) metadata.get("parent_text");
                metadata.put("parent_text", deepClean(parentText));
            }

            return Document.builder()
                    .id(doc.getId())
                    .text(cleanedText)
                    .metadata(metadata)
                    .build();
        }).collect(Collectors.toList());
    }

    private String deepClean(String text) {
        if (text == null)
            return "";
        // 移除 ASCII 控制字符
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        // 规范化空白字符 (将连续空格合并，但保留换行结构)
        text = text.replaceAll("[^\\S\\r\\n]{2,}", " ");
        // 移除过多的连续空行 (保留段落结构 \n\n)
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    /**
     * 采用 Parent-Child (小到大) 检索策略切分文本并管理图片
     */
    private List<Document> splitParentChildAndAssociate(
            List<PageContent> pageContents,
            List<PdfImageInfo> allImages,
            String documentName) {

        List<Document> resultDocuments = new ArrayList<>();

        // 1. 合并全文并记录页码边界
        StringBuilder fullText = new StringBuilder();
        TreeMap<Integer, Integer> offsetToPageMap = new TreeMap<>();
        for (PageContent pc : pageContents) {
            offsetToPageMap.put(fullText.length(), pc.pageNumber());
            fullText.append(pc.text()).append("\n\n"); // 页面间留空，避免语义强行连接
        }

        // 2. 语义化递归切分 (优先切分段落、句子)
        // 此处模拟 RecursiveCharacterTextSplitter 逻辑
        List<String> childTexts = recursiveSplit(fullText.toString(), CHILD_CHUNK_SIZE, CHILD_CHUNK_OVERLAP);

        // 3. 预分组图片
        Map<Integer, List<Map<String, Object>>> imagesByPage = allImages.stream()
                .collect(Collectors.groupingBy(PdfImageInfo::getPageNumber,
                        Collectors.mapping(img -> Map.of(
                                "fileName", (Object) img.getFileName(),
                                "ossUrl", img.getOssUrl(),
                                "pageNumber", img.getPageNumber()), Collectors.toList())));

        int currentSearchOffset = 0;
        for (int i = 0; i < childTexts.size(); i++) {
            String childText = childTexts.get(i);

            // 确定子块所在的原文位置（由于 Splitter 的特性，需向后搜索）
            int startOffset = fullText.indexOf(childText, currentSearchOffset);
            if (startOffset == -1)
                startOffset = currentSearchOffset; // 降级处理
            int endOffset = startOffset + childText.length();
            currentSearchOffset = startOffset + (childText.length() / 2); // 移动搜索起点

            // 确定涉及的页码
            Integer startPage = offsetToPageMap.floorEntry(startOffset).getValue();
            Integer endPage = offsetToPageMap.floorEntry(Math.max(0, endOffset - 1)).getValue();

            // 4. 构建 Parent Context (在子块前后扩展一定范围)
            int parentStart = Math.max(0, startOffset - PARENT_CONTEXT_SIZE);
            int parentEnd = Math.min(fullText.length(), endOffset + PARENT_CONTEXT_SIZE);
            String parentText = fullText.substring(parentStart, parentEnd).trim();

            // 5. 关联图片 (涉及的所有页面的图片均可见)
            List<Map<String, Object>> associatedImages = new ArrayList<>();
            for (int p = startPage; p <= endPage; p++) {
                associatedImages.addAll(imagesByPage.getOrDefault(p, Collections.emptyList()));
            }

            // 构建元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentName", documentName);
            metadata.put("pageNumber", startPage == endPage ? String.valueOf(startPage) : startPage + "-" + endPage);
            metadata.put("childIndex", i + 1);
            metadata.put("timestamp", Instant.now().toString());
            metadata.put("is_child", true);
            metadata.put("parent_text", parentText);

            if (!associatedImages.isEmpty()) {
                metadata.put("images", associatedImages);
            }

            Document doc = Document.builder()
                    .id(UUID.randomUUID().toString())
                    .text(childText)
                    .metadata(metadata)
                    .build();

            resultDocuments.add(doc);
        }

        return resultDocuments;
    }

    /**
     * 简易递归切分器：优先尝试在段落(\n\n)、换行(\n)、标点(。|.)、空格处切分
     */
    private List<String> recursiveSplit(String text, int limit, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty())
            return chunks;

        String[] separators = { "\n\n", "\n", "。", "！", "？", ". ", " " };
        recursiveAction(text, limit, overlap, separators, 0, chunks);
        return chunks;
    }

    private void recursiveAction(String text, int limit, int overlap, String[] separators, int sepIdx,
            List<String> chunks) {
        if (text.length() <= limit) {
            chunks.add(text);
            return;
        }

        if (sepIdx >= separators.length) {
            // 实在分不开了，强行按长度切分
            for (int i = 0; i < text.length(); i += (limit - overlap)) {
                int end = Math.min(i + limit, text.length());
                chunks.add(text.substring(i, end));
                if (end == text.length())
                    break;
            }
            return;
        }

        String sep = separators[sepIdx];
        String[] parts = text.split("(?<=" + Pattern.quote(sep) + ")"); // 使用正向后瞻保留分隔符

        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (current.length() + part.length() > limit) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    // 计算 Overlap: 从当前块尾部回退
                    int overlapStart = Math.max(0, current.length() - overlap);
                    String overlapText = current.substring(overlapStart);
                    current = new StringBuilder(overlapText);
                }

                // 如果单部分就超过了 limit，继续递归
                if (part.length() > limit) {
                    recursiveAction(part, limit, overlap, separators, sepIdx + 1, chunks);
                    continue;
                }
            }
            current.append(part);
        }
        if (current.length() > overlap) { // 避免只剩下 overlap 部分
            chunks.add(current.toString());
        }
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
