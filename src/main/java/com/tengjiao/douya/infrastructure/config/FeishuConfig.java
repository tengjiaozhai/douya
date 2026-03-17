package com.tengjiao.douya.infrastructure.config;

import com.tengjiao.douya.application.service.EatingMasterApp;
import com.tengjiao.douya.entity.feishu.FeishuMessageEvent;
import com.tengjiao.douya.entity.feishu.FeishuMessageSendRequest;
import com.tengjiao.douya.entity.feishu.content.FeishuImageContent;
import com.tengjiao.douya.entity.feishu.content.FeishuPostContent;
import com.tengjiao.douya.entity.feishu.content.FeishuPostContent.PostElement;
import com.tengjiao.douya.entity.feishu.content.FeishuPostMessageContent;
import com.tengjiao.douya.entity.feishu.content.FeishuTextContent;
import com.tengjiao.douya.infrastructure.external.feishu.FeiShuGetMessageResourceUtils;
import com.tengjiao.douya.infrastructure.external.feishu.FeishuService;


import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.CustomEventHandler;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.P2URLPreviewGetHandler;
import com.lark.oapi.event.cardcallback.model.*;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2ChatAccessEventBotP2pChatEnteredV1;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 飞书 WebSocket 长连接配置
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Configuration
@Slf4j
@EnableConfigurationProperties(FeishuProperties.class)
public class FeishuConfig {
    private static final long FEISHU_MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Pattern MARKDOWN_IMG_PATTERN = Pattern.compile("!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)");
    private static final Pattern ASSET_IMG_PATTERN = Pattern.compile(
            "(?im)^\\s*\\[[^\\]]*\\]\\s*:\\s*ossUrl\\s*=\\s*(https?://\\S+)\\s*$");
    private static final Pattern GENERIC_OSS_URL_PATTERN = Pattern.compile(
            "(?i)ossUrl\\s*=\\s*(https?://[^\\s\"'\\\\)\\]]+)");

    private final FeishuProperties feishuProperties;

    public FeishuConfig(FeishuProperties feishuProperties) {
        this.feishuProperties = feishuProperties;
    }

    @Resource
    private EatingMasterApp eatingMasterApp;
    @Resource
    private FeishuService feishuService;

    // 消息去重缓存：保存最近 1000 条消息 ID，防止飞书重试导致重复思考
    private final Map<String, Boolean> messageIdCache = Collections
            .synchronizedMap(new LinkedHashMap<String, Boolean>(1001, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 1000;
                }
            });

    /**
     * 创建飞书 WebSocket 客户端
     */
    @Bean
    public Client feishuWsClient() {
        // 1. 配置事件处理器
        // onP2MessageReceiveV1 为接收消息 v2.0；onCustomizedEvent 内的 message 为接收消息 v1.0。
        EventDispatcher eventHandler = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        log.info("[Feishu] 收到私聊消息: {}", Jsons.DEFAULT.toJson(event.getEvent()));
                        String json = Jsons.DEFAULT.toJson(event.getEvent());
                        FeishuMessageEvent feishuMessageEvent = Jsons.DEFAULT.fromJson(json, FeishuMessageEvent.class);
                        FeishuMessageEvent.Message message = feishuMessageEvent.getMessage();
                        String messageId = message.getMessageId();
                        // 幂等检查：如果消息正在处理或已处理，直接跳过
                        if (messageIdCache.putIfAbsent(messageId, true) != null) {
                            log.info("[Feishu] 消息 {} 正在处理中或已处理，跳过重试", messageId);
                            return;
                        }
                        // 异步处理大模型逻辑，立即返回给飞书以避免 3s 超时重试
                        Thread.startVirtualThread(() -> {
                            try {
                                String content = message.getContent();
                                String messageType = message.getMessageType();
                                String userId = feishuMessageEvent.getSender().getSenderId().getUserId();

                                log.info("[Feishu] 开始异步处理私聊消息: {}", messageId);

                                switch (messageType) {
                                    case "text" -> {
                                        FeishuTextContent feishuTextContent = Jsons.DEFAULT.fromJson(content,
                                                FeishuTextContent.class);
                                        handleUserQuery(userId, feishuTextContent == null ? "" : feishuTextContent.getText());
                                    }
                                    case "post" -> {
                                        PostMessageParts postParts = parsePostMessageParts(content);
                                        log.info("[Feishu] 接收到 post 富文本消息，文本长度: {}, 图片数量: {}",
                                                postParts.text().length(), postParts.imageKeys().size());
                                        handlePostMessage(userId, messageId, postParts);
                                    }
                                    case "image" -> {
                                        FeishuImageContent feishuImageContent = Jsons.DEFAULT.fromJson(content,
                                                FeishuImageContent.class);

                                        // 获取项目根目录下的 src/main/resources/temp
                                        ApplicationHome home = new ApplicationHome(getClass());
                                        File sourceDir = home.getSource();
                                        String tempPath = sourceDir.getParentFile().getParentFile().getAbsolutePath()
                                                + File.separator + "src" + File.separator + "main" + File.separator
                                                + "resources" + File.separator + "temp";

                                        File tempDir = new File(tempPath);
                                        if (!tempDir.exists()) {
                                            tempDir.mkdirs();
                                        }

                                        String fileName = feishuImageContent.getImageKey() + ".png";
                                        String fullPath = tempPath + File.separator + fileName;

                                        log.info("[Feishu] 开始下载图片资源到: {}", fullPath);
                                        FeiShuGetMessageResourceUtils.getMessageResource(
                                                feishuProperties.getAppId(),
                                                feishuProperties.getAppSecret(),
                                                messageId,
                                                feishuImageContent.getImageKey(),
                                                messageType,
                                                fullPath);
                                        log.info("[Feishu] 图片资源下载完成: {}", fullPath);

                                        // 暂存图片路径，不立即分析
                                        eatingMasterApp.setPendingImage(userId, fullPath);

                                        // 回复用户，引导表达意图
                                        FeishuTextContent responseContent = new FeishuTextContent();
                                        String welcomeBack = "收到图片啦！📸\n你想让我针对这张图帮你做点什么？（比如分析它的内容、识别文字，或者告诉我你此刻的想法）";
                                        responseContent.setText(welcomeBack);
                                        feishuService.sendMessage("user_id",
                                                new FeishuMessageSendRequest(userId, "text",
                                                        Jsons.DEFAULT.toJson(responseContent),
                                                        UUID.randomUUID().toString()));
                                    }
                                    default -> {}
                                }
                            } catch (Exception e) {
                                log.error("[Feishu] 异步处理消息 {} 异常", messageId, e);
                            }
                        });
                    }
                })
                .onCustomizedEvent("out_approval", new CustomEventHandler() {
                    @Override
                    public void handle(EventReq event) throws Exception {
                        log.info("[Feishu] 收到自定义事件 [out_approval]: {}",
                                new String(event.getBody(), StandardCharsets.UTF_8));
                    }
                })
                // 监听「卡片回传交互 card.action.trigger」
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) throws Exception {
                        log.info("[ P2CardActionTrigger access ], data: {}\n", Jsons.DEFAULT.toJson(event.getEvent()));
                        P2CardActionTriggerResponse resp = new P2CardActionTriggerResponse();
                        CallBackToast toast = new CallBackToast();
                        toast.setType("info");
                        toast.setContent("卡片交互成功 from Java SDk");
                        resp.setToast(toast);
                        return resp;
                    }
                })
                // 监听「拉取链接预览数据 url.preview.get」
                .onP2URLPreviewGet(new P2URLPreviewGetHandler() {
                    @Override
                    public P2URLPreviewGetResponse handle(P2URLPreviewGet event) throws Exception {
                        log.info("[ P2URLPreviewGet access ], data: {}\n", Jsons.DEFAULT.toJson(event.getEvent()));
                        P2URLPreviewGetResponse resp = new P2URLPreviewGetResponse();
                        URLPreviewGetInline inline = new URLPreviewGetInline();
                        inline.setTitle("链接预览测试fromJavaSDK");
                        resp.setInline(inline);
                        return resp;
                    }
                })
                .onP2ChatAccessEventBotP2pChatEnteredV1(new ImService.P2ChatAccessEventBotP2pChatEnteredV1Handler() {
                    @Override
                    public void handle(P2ChatAccessEventBotP2pChatEnteredV1 event) throws Exception {
                        String userId = event.getEvent().getOperatorId().getUserId();
                        log.info("[用户进入应用会话] 用户ID: {}, data: {}\n", userId, Jsons.DEFAULT.toJson(event.getEvent()));

                        // 异步发送欢迎语
                        Thread.startVirtualThread(() -> {
                            try {
                                String welcomeMsg = eatingMasterApp.welcome(userId);
                                FeishuTextContent content = new FeishuTextContent();
                                content.setText(welcomeMsg);
                                feishuService.sendMessage("user_id", new FeishuMessageSendRequest(userId, "text",
                                        Jsons.DEFAULT.toJson(content), UUID.randomUUID().toString()));
                            } catch (Exception e) {
                                log.error("[Feishu] 发送欢迎语异常", e);
                            }
                        });
                    }
                })
                // 消息已读
                .onP2MessageReadV1(new ImService.P2MessageReadV1Handler() {
                    @Override
                    public void handle(P2MessageReadV1 event) throws Exception {
                        log.info("[ onP2MessageReadV1 access ], data: {}\n", Jsons.DEFAULT.toJson(event.getEvent()));
                    }
                })
                .build();

        // 2. 构建客户端
        return new Client.Builder(feishuProperties.getAppId(), feishuProperties.getAppSecret())
                .eventHandler(eventHandler)
                .build();
    }

    private void handleUserQuery(String userId, String userQuery) {
        String safeQuery = userQuery == null ? "" : userQuery.trim();

        // 检查是否有待处理的图片
        String pendingImagePath = eatingMasterApp.getPendingImage(userId);
        if (pendingImagePath != null && !pendingImagePath.isEmpty()) {
            log.info("[Feishu] 发现用户 {} 有待处理图片，开始结合处理", userId);
            sendTextMessage(userId, "收到！正在结合你刚才发的图片进行深度分析...");

            // 调用视觉分析 + 专家对话
            String visionInfo = eatingMasterApp.visionAnalyze(pendingImagePath, safeQuery, userId);
            String aiResponse = eatingMasterApp.ask(visionInfo, userId);

            sendAiResponse(userId, aiResponse);
            eatingMasterApp.clearPendingImage(userId);
            return;
        }

        // 正常聊天流程
        sendTextMessage(userId, "稍等哦，本大师正在思考...");
        String aiResponse = eatingMasterApp.ask(safeQuery, userId);
        sendAiResponse(userId, aiResponse);
    }

    private void sendAiResponse(String userId, String aiResponse) {
        String reply = aiResponse == null ? "" : aiResponse;
        if (!sendPostIfContainsImages(userId, reply)) {
            sendTextMessage(userId, reply);
        }
    }

    private void sendTextMessage(String userId, String text) {
        FeishuTextContent response = new FeishuTextContent();
        response.setText(text == null ? "" : text);
        feishuService.sendMessage("user_id",
                new FeishuMessageSendRequest(userId, "text",
                        Jsons.DEFAULT.toJson(response),
                        UUID.randomUUID().toString()));
    }

    private void handlePostMessage(String userId, String messageId, PostMessageParts postParts) {
        String text = postParts.text() == null ? "" : postParts.text().trim();
        List<String> imageKeys = postParts.imageKeys();

        if (imageKeys == null || imageKeys.isEmpty()) {
            handleUserQuery(userId, text);
            return;
        }

        sendTextMessage(userId, "收到图文消息，正在先解读图片，再结合文字给你完整回答...");

        List<String> visionInfos = new ArrayList<>();
        String visionQuery = text.isBlank() ? "请解读图片内容并提取关键信息。" : text;
        for (int i = 0; i < imageKeys.size(); i++) {
            String imageKey = imageKeys.get(i);
            try {
                String fullPath = downloadImageFromMessage(messageId, imageKey);
                String visionInfo = eatingMasterApp.visionAnalyze(fullPath, visionQuery, userId);
                if (visionInfo != null && !visionInfo.isBlank()) {
                    visionInfos.add("图片" + (i + 1) + "解读：" + visionInfo.trim());
                }
            } catch (Exception e) {
                log.error("[Feishu] post 图片解读失败. messageId={}, imageKey={}", messageId, imageKey, e);
            }
        }

        String finalQuery = buildPostCombinedQuery(text, visionInfos);
        String aiResponse = eatingMasterApp.ask(finalQuery, userId);
        sendAiResponse(userId, aiResponse);
    }

    private PostMessageParts parsePostMessageParts(String content) {
        if (content == null || content.isBlank()) {
            return new PostMessageParts("", List.of());
        }

        FeishuPostContent postContent = null;
        try {
            FeishuPostMessageContent messageContent = Jsons.DEFAULT.fromJson(content, FeishuPostMessageContent.class);
            if (messageContent != null) {
                postContent = messageContent.resolvePostContent();
            }
        } catch (Exception e) {
            log.warn("[Feishu] post 消息包装结构解析失败，将尝试扁平结构解析", e);
        }

        if (postContent == null) {
            try {
                postContent = Jsons.DEFAULT.fromJson(content, FeishuPostContent.class);
            } catch (Exception e) {
                log.warn("[Feishu] post 消息内容解析失败: {}", content, e);
                return new PostMessageParts("", List.of());
            }
        }
        return extractPostMessageParts(postContent);
    }

    private PostMessageParts extractPostMessageParts(FeishuPostContent postContent) {
        if (postContent == null) {
            return new PostMessageParts("", List.of());
        }

        StringBuilder builder = new StringBuilder();
        Set<String> imageKeyCollector = new LinkedHashSet<>();

        if (postContent.getTitle() != null && !postContent.getTitle().isBlank()) {
            builder.append(postContent.getTitle().trim()).append('\n');
        }

        List<List<PostElement>> paragraphs = postContent.getContent();
        if (paragraphs != null) {
            for (List<PostElement> paragraph : paragraphs) {
                if (paragraph == null || paragraph.isEmpty()) {
                    continue;
                }
                StringBuilder line = new StringBuilder();
                for (PostElement element : paragraph) {
                    String fragment = extractPostTextFromElement(element, imageKeyCollector);
                    if (fragment == null || fragment.isBlank()) {
                        continue;
                    }
                    if (line.length() > 0 && !fragment.startsWith("\n")) {
                        line.append(' ');
                    }
                    line.append(fragment);
                }
                String lineText = line.toString().trim();
                if (!lineText.isBlank()) {
                    if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                        builder.append('\n');
                    }
                    builder.append(lineText).append('\n');
                }
            }
        }
        return new PostMessageParts(builder.toString().trim(), new ArrayList<>(imageKeyCollector));
    }

    private String extractPostTextFromElement(PostElement element, Set<String> imageKeyCollector) {
        if (element == null) {
            return "";
        }
        String tag = element.getTag() == null ? "" : element.getTag().trim().toLowerCase();
        return switch (tag) {
            case "text", "md", "code_block" -> element.getText() == null ? "" : element.getText().trim();
            case "a" -> {
                String text = element.getText() == null ? "" : element.getText().trim();
                String href = element.getHref() == null ? "" : element.getHref().trim();
                if (!text.isBlank() && !href.isBlank() && !text.contains(href)) {
                    yield text + " (" + href + ")";
                }
                yield !text.isBlank() ? text : href;
            }
            case "at" -> {
                String userName = element.getUserName() == null ? "" : element.getUserName().trim();
                String mentionUserId = element.getUserId() == null ? "" : element.getUserId().trim();
                if (!userName.isBlank()) {
                    yield "@" + userName;
                }
                if (!mentionUserId.isBlank()) {
                    yield "@user_id:" + mentionUserId;
                }
                yield "@someone";
            }
            case "img" -> {
                String imageKey = element.getImageKey() == null ? "" : element.getImageKey().trim();
                if (!imageKey.isBlank()) {
                    imageKeyCollector.add(imageKey);
                }
                yield "";
            }
            case "media" -> {
                String fileKey = element.getFileKey() == null ? "" : element.getFileKey().trim();
                if (!fileKey.isBlank()) {
                    yield "[媒体]: file_key=" + fileKey;
                }
                yield "[媒体]";
            }
            case "emotion" -> {
                String emojiType = element.getEmojiType() == null ? "" : element.getEmojiType().trim();
                yield emojiType.isBlank() ? "[表情]" : "[表情:" + emojiType + "]";
            }
            case "hr" -> "\n---";
            default -> element.getText() == null ? "" : element.getText().trim();
        };
    }

    private String downloadImageFromMessage(String messageId, String imageKey) throws Exception {
        String tempPath = resolveFeishuTempPath();
        File tempDir = new File(tempPath);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IllegalStateException("创建临时目录失败: " + tempPath);
        }

        String fileName = imageKey + "_" + UUID.randomUUID() + ".png";
        String fullPath = tempPath + File.separator + fileName;
        log.info("[Feishu] 开始下载 post 图片资源到: {}", fullPath);
        FeiShuGetMessageResourceUtils.getMessageResource(
                feishuProperties.getAppId(),
                feishuProperties.getAppSecret(),
                messageId,
                imageKey,
                "image",
                fullPath);
        log.info("[Feishu] post 图片资源下载完成: {}", fullPath);
        return fullPath;
    }

    private String resolveFeishuTempPath() {
        ApplicationHome home = new ApplicationHome(getClass());
        File sourceDir = home.getSource();
        return sourceDir.getParentFile().getParentFile().getAbsolutePath()
                + File.separator + "src" + File.separator + "main" + File.separator
                + "resources" + File.separator + "temp";
    }

    private String buildPostCombinedQuery(String postText, List<String> visionInfos) {
        String safeText = postText == null ? "" : postText.trim();
        if (visionInfos == null || visionInfos.isEmpty()) {
            return safeText.isBlank() ? "请根据用户发送的图片给出分析和建议。" : safeText;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("用户发送了一条图文消息，请先参考图片解读结果，再结合用户文字完成回答。");
        if (!safeText.isBlank()) {
            sb.append("\n\n[用户文字]\n").append(safeText);
        }
        sb.append("\n\n[图片解读]\n");
        for (int i = 0; i < visionInfos.size(); i++) {
            sb.append(i + 1).append(". ").append(visionInfos.get(i)).append('\n');
        }
        sb.append("\n请输出最终答复。");
        return sb.toString().trim();
    }

    /**
     * 解析 AI 回复中的图片并发送 Feishu post 富文本。
     */
    private boolean sendPostIfContainsImages(String userId, String aiResponse) {
        RichMessageParts parts = parseRichMessage(aiResponse);
        if (parts.imageUrls().isEmpty()) {
            return false;
        }

        log.info("[Feishu] 检测到图片资产，使用 post 富文本发送. imageCount={}", parts.imageUrls().size());

        FeishuPostContent postContent = new FeishuPostContent();
        postContent.setTitle("");
        postContent.setContent(buildPostParagraphs(parts.text(), parts.imageUrls()));

        FeishuPostMessageContent sendContent = new FeishuPostMessageContent();
        sendContent.setZhCn(postContent);

        feishuService.sendMessage("user_id",
                new FeishuMessageSendRequest(userId, "post",
                        Jsons.DEFAULT.toJson(sendContent),
                        UUID.randomUUID().toString()));
        return true;
    }

    private RichMessageParts parseRichMessage(String aiResponse) {
        String raw = aiResponse == null ? "" : aiResponse;
        Set<String> urls = new LinkedHashSet<>();

        collectUrls(MARKDOWN_IMG_PATTERN, raw, urls);
        collectUrls(ASSET_IMG_PATTERN, raw, urls);
        collectUrls(GENERIC_OSS_URL_PATTERN, raw, urls);

        String cleaned = MARKDOWN_IMG_PATTERN.matcher(raw).replaceAll("");
        cleaned = ASSET_IMG_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("(?im)^\\s*相关图片资产\\s*:?\\s*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*鍏宠仈鍥剧墖\\s*:?\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^[ \\t]+$", "");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();

        return new RichMessageParts(cleaned, new ArrayList<>(urls));
    }

    private void collectUrls(Pattern pattern, String text, Set<String> collector) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String url = normalizeUrl(matcher.group(1));
            if (url != null && !url.isBlank()) {
                collector.add(url);
            }
        }
    }

    private List<List<PostElement>> buildPostParagraphs(String text, List<String> imageUrls) {
        List<List<PostElement>> paragraphs = new ArrayList<>();

        if (text != null && !text.isBlank()) {
            String[] blocks = text.split("\\n\\s*\\n");
            for (String block : blocks) {
                String paragraphText = block == null ? "" : block.trim();
                if (paragraphText.isBlank()) {
                    continue;
                }
                PostElement textElem = new PostElement();
                textElem.setTag("text");
                textElem.setText(paragraphText);
                paragraphs.add(List.of(textElem));
            }
        }

        for (String imageUrl : imageUrls) {
            try {
                String imageKey = uploadRemoteImageToFeishu(imageUrl);
                PostElement imgElem = new PostElement();
                imgElem.setTag("img");
                imgElem.setImageKey(imageKey);
                paragraphs.add(List.of(imgElem));
            } catch (Exception e) {
                log.error("[Feishu] 图片上传失败, 回退链接发送: {}", imageUrl, e);
                PostElement linkElem = new PostElement();
                linkElem.setTag("a");
                linkElem.setText("[图片链接]");
                linkElem.setHref(imageUrl);
                paragraphs.add(List.of(linkElem));
            }
        }

        if (paragraphs.isEmpty()) {
            PostElement emptyText = new PostElement();
            emptyText.setTag("text");
            emptyText.setText("(empty message)");
            paragraphs.add(List.of(emptyText));
        }
        return paragraphs;
    }

    private String uploadRemoteImageToFeishu(String imageUrl) throws Exception {
        String suffix = guessImageSuffix(imageUrl);
        File tempImg = File.createTempFile("feishu_upload_", suffix);
        File compressedImg = null;
        try (InputStream in = java.net.URI.create(imageUrl).toURL().openStream()) {
            Files.copy(in, tempImg.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            File uploadFile = tempImg;
            if (tempImg.length() > FEISHU_MAX_IMAGE_BYTES) {
                log.warn("[Feishu] 图片超过10MB，尝试压缩后再上传. url={}, size={} bytes", imageUrl, tempImg.length());
                compressedImg = compressImageToLimit(tempImg, FEISHU_MAX_IMAGE_BYTES);
                uploadFile = compressedImg;
                log.info("[Feishu] 图片压缩完成. url={}, size={} bytes", imageUrl, uploadFile.length());
            }
            return feishuService.uploadImage(uploadFile);
        } finally {
            if (compressedImg != null && compressedImg.exists() && !compressedImg.delete()) {
                log.warn("[Feishu] 压缩临时图片删除失败: {}", compressedImg.getAbsolutePath());
            }
            if (!tempImg.delete()) {
                log.warn("[Feishu] 临时图片文件删除失败: {}", tempImg.getAbsolutePath());
            }
        }
    }

    private File compressImageToLimit(File sourceFile, long maxBytes) throws IOException {
        BufferedImage original = ImageIO.read(sourceFile);
        if (original == null) {
            throw new IOException("图片格式不支持压缩，文件大小: " + sourceFile.length());
        }

        BufferedImage rgbImage = toRgbImage(original);
        int width = rgbImage.getWidth();
        int height = rgbImage.getHeight();

        IOException lastException = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            double scale = attempt < 3 ? 1.0 : Math.pow(0.85d, attempt - 2);
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            float quality = Math.max(0.35f, 0.92f - (attempt * 0.06f));

            BufferedImage candidateImage = (targetWidth == width && targetHeight == height)
                    ? rgbImage
                    : resizeImage(rgbImage, targetWidth, targetHeight);

            File candidate = File.createTempFile("feishu_upload_compressed_", ".jpg");
            try {
                writeJpeg(candidateImage, candidate, quality);
                if (candidate.length() <= maxBytes) {
                    return candidate;
                }
                if (!candidate.delete()) {
                    log.warn("[Feishu] 压缩候选文件删除失败: {}", candidate.getAbsolutePath());
                }
            } catch (IOException e) {
                lastException = e;
                if (!candidate.delete()) {
                    log.warn("[Feishu] 压缩失败候选文件删除失败: {}", candidate.getAbsolutePath());
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("图片压缩后仍超过 10MB，原始大小: " + sourceFile.length());
    }

    private BufferedImage toRgbImage(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rgb.createGraphics();
        try {
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, source.getWidth(), source.getHeight());
            g2d.drawImage(source, 0, 0, null);
        } finally {
            g2d.dispose();
        }
        return rgb;
    }

    private BufferedImage resizeImage(BufferedImage source, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(source, 0, 0, width, height, null);
        } finally {
            g2d.dispose();
        }
        return resized;
    }

    private void writeJpeg(BufferedImage image, File output, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("当前运行环境没有可用 JPEG 编码器");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private String guessImageSuffix(String imageUrl) {
        String lower = imageUrl == null ? "" : imageUrl.toLowerCase();
        if (lower.contains(".png")) {
            return ".png";
        }
        if (lower.contains(".webp")) {
            return ".webp";
        }
        if (lower.contains(".gif")) {
            return ".gif";
        }
        return ".jpg";
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.trim().replaceAll("[\\]\\)>,.;]+$", "");
    }

    private record PostMessageParts(String text, List<String> imageKeys) {
    }

    private record RichMessageParts(String text, List<String> imageUrls) {
    }

    /**
     * 项目启动后建立长连接
     */
    @Bean
    public CommandLineRunner startFeishuClient(Client feishuWsClient) {
        return args -> {
            log.info("正在尝试建立飞书 WebSocket 连接...");
            try {
                feishuWsClient.start();
                log.info("飞书 WebSocket 连接已建立");
            } catch (Exception e) {
                log.error("飞书 WebSocket 连接失败", e);
            }
        };
    }
}
