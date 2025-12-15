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
                        String json = Jsons.DEFAULT.toJson(event.getEvent());
                        FeishuMessageEvent feishuMessageEvent = Jsons.DEFAULT.fromJson(json, FeishuMessageEvent.class);
                        FeishuMessageEvent.Message message = feishuMessageEvent.getMessage();
                        String content = message.getContent();
                        String messageType = message.getMessageType();
                        log.info("[Feishu] 私聊消息内容: {}", message);
                        switch (messageType) {
                            case "text" -> {
                                FeishuTextContent feishuTextContent = Jsons.DEFAULT.fromJson(content, FeishuTextContent.class);
                                String userId = feishuMessageEvent.getSender().getSenderId().getUserId();
                                String response = eatingMasterApp.ask(feishuTextContent.getText(),userId);
                                String uuid = UUID.randomUUID().toString();
                                feishuTextContent.setText(response);
                                String requestContent = Jsons.DEFAULT.toJson(feishuTextContent);
                                FeishuMessageSendRequest request = new FeishuMessageSendRequest(userId, messageType,
                                        requestContent, uuid);
                                feishuService.sendMessage("user_id",
                                        request);
                            }
                        }

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
