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

import java.io.File;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

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
                                        String userQuery = feishuTextContent.getText();

                                        // 检查是否有待处理的图片
                                        String pendingImagePath = eatingMasterApp.getPendingImage(userId);
                                        if (pendingImagePath != null && !pendingImagePath.isEmpty()) {
                                            log.info("[Feishu] 发现用户 {} 有待处理图片，开始结合处理", userId);

                                            // 1. 立即回复“正在分析”
                                            feishuTextContent.setText("收到！正在结合你刚才发的图片进行深度分析...");
                                            feishuService.sendMessage("user_id",
                                                    new FeishuMessageSendRequest(userId, messageType,
                                                            Jsons.DEFAULT.toJson(feishuTextContent),
                                                            UUID.randomUUID().toString()));

                                            // 2. 调用视觉分析 + 专家对话
                                            String visionInfo = eatingMasterApp.visionAnalyze(pendingImagePath,
                                                    userQuery, userId);
                                            String aiResponse = eatingMasterApp.ask(visionInfo, userId);

                                            // 3. 发送最终结果并清除状态
                                            feishuTextContent.setText(aiResponse);
                                            feishuService.sendMessage("user_id",
                                                    new FeishuMessageSendRequest(userId, messageType,
                                                            Jsons.DEFAULT.toJson(feishuTextContent),
                                                            UUID.randomUUID().toString()));
                                            eatingMasterApp.clearPendingImage(userId);
                                        } else {
                                            // 正常聊天流程
                                            // 1. 立即回复“正在思考”
                                            feishuTextContent.setText("稍等哦，本大师正在思考...");
                                            feishuService.sendMessage("user_id",
                                                    new FeishuMessageSendRequest(userId, messageType,
                                                            Jsons.DEFAULT.toJson(feishuTextContent),
                                                            UUID.randomUUID().toString()));

                                            // 2. 调用大模型（耗时操作）
                                            String aiResponse = eatingMasterApp.ask(userQuery, userId);

                                            // 3. 解析回复内容，检查是否包含 Markdown 图片
                                            Pattern imgPattern = Pattern
                                                    .compile("!\\[(.*?)\\]\\((.*?)\\)");
                                            java.util.regex.Matcher matcher = imgPattern.matcher(aiResponse);

                                            if (matcher.find()) {
                                                log.info("[Feishu] 检测到回复中包含图片，正在构建富文本消息...");
                                                // 重置 matcher
                                                matcher.reset();

                                                FeishuPostContent postContent = new FeishuPostContent();
                                                postContent.setTitle("");

                                                List<PostElement> elements = new ArrayList<>();
                                                int lastIndex = 0;

                                                while (matcher.find()) {
                                                    // 添加前面的文本
                                                    String preText = aiResponse.substring(lastIndex, matcher.start());
                                                    if (!preText.isEmpty()) {
                                                        PostElement textElem = new PostElement();
                                                        textElem.setTag("text");
                                                        textElem.setText(preText);
                                                        elements.add(textElem);
                                                    }

                                                    // 处理图片
                                                    String imgUrl = matcher.group(2);
                                                    try {
                                                        // 下载图片到临时文件
                                                        String suffix = imgUrl.toLowerCase().endsWith(".png") ? ".png"
                                                                : ".jpg";
                                                        File tempImg = File
                                                                .createTempFile("feishu_upload_", suffix);
                                                        InputStream in = java.net.URI.create(imgUrl).toURL()
                                                                .openStream();
                                                        Files.copy(in, tempImg.toPath(),
                                                                StandardCopyOption.REPLACE_EXISTING);
                                                        in.close();

                                                        // 上传到飞书
                                                        String imageKey = feishuService.uploadImage(tempImg);

                                                        // 添加图片元素
                                                        PostElement imgElem = new PostElement();
                                                        imgElem.setTag("img");
                                                        imgElem.setImageKey(imageKey);
                                                        elements.add(imgElem);

                                                        // 删除临时文件
                                                        tempImg.delete();
                                                    } catch (Exception e) {
                                                        log.error("[Feishu] 图片处理失败: {}", imgUrl, e);
                                                        // 降级为链接文本
                                                        PostElement linkElem = new PostElement();
                                                        linkElem.setTag("a");
                                                        linkElem.setText("[图片链接]");
                                                        linkElem.setHref(imgUrl);
                                                        elements.add(linkElem);
                                                    }

                                                    lastIndex = matcher.end();
                                                }

                                                // 添加剩余文本
                                                String tailText = aiResponse.substring(lastIndex);
                                                if (!tailText.isEmpty()) {
                                                    PostElement textElem = new PostElement();
                                                    textElem.setTag("text");
                                                    textElem.setText(tailText);
                                                    elements.add(textElem);
                                                }

                                                postContent.setContent(List.of(elements));

                                                // 封装为发送格式 {"zh_cn": ...}
                                                FeishuPostMessageContent sendContent = new FeishuPostMessageContent();
                                                sendContent.setZhCn(postContent);

                                                feishuService.sendMessage("user_id",
                                                        new FeishuMessageSendRequest(userId, "post",
                                                                Jsons.DEFAULT.toJson(sendContent),
                                                                UUID.randomUUID().toString()));

                                            } else {
                                                // 纯文本消息
                                                feishuTextContent.setText(aiResponse);
                                                feishuService.sendMessage("user_id",
                                                        new FeishuMessageSendRequest(userId, messageType,
                                                                Jsons.DEFAULT.toJson(feishuTextContent),
                                                                UUID.randomUUID().toString()));
                                            }
                                        }
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
