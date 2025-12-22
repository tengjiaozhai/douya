package com.tengjiao.douya.config;

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
import com.tengjiao.douya.app.EatingMasterApp;
import com.tengjiao.douya.entity.feishu.FeishuMessageEvent;
import com.tengjiao.douya.entity.feishu.FeishuMessageSendRequest;
import com.tengjiao.douya.entity.feishu.content.FeishuTextContent;
import com.tengjiao.douya.service.FeishuService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
    private final Map<String, Boolean> messageIdCache = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(1001, 0.75f, true) {
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

                                if ("text".equals(messageType)) {
                                    FeishuTextContent feishuTextContent = Jsons.DEFAULT.fromJson(content, FeishuTextContent.class);
                                    String userQuery = feishuTextContent.getText();

                                    // 1. 立即回复“正在思考”提升用户体验
                                    feishuTextContent.setText("稍等哦，本大师正在思考...");
                                    feishuService.sendMessage("user_id", new FeishuMessageSendRequest(userId, messageType, Jsons.DEFAULT.toJson(feishuTextContent), UUID.randomUUID().toString()));

                                    // 2. 调用大模型（耗时操作）
                                    String aiResponse = eatingMasterApp.ask(userQuery, userId);

                                    // 3. 发送最终结果
                                    feishuTextContent.setText(aiResponse);
                                    feishuService.sendMessage("user_id", new FeishuMessageSendRequest(userId, messageType, Jsons.DEFAULT.toJson(feishuTextContent), UUID.randomUUID().toString()));
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
                        log.info("[Feishu] 收到自定义事件 [out_approval]: {}", new String(event.getBody(), StandardCharsets.UTF_8));
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
                        log.info("[用户进入应用会话], data: {}\n", Jsons.DEFAULT.toJson(event.getEvent()));
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
