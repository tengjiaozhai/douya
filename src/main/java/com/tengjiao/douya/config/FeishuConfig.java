package com.tengjiao.douya.config;

import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.CustomEventHandler;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.P2URLPreviewGetHandler;
import com.lark.oapi.event.cardcallback.model.*;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

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
