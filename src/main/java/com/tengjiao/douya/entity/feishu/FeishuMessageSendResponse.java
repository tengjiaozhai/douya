package com.tengjiao.douya.entity.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 飞书发送消息响应体
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuMessageSendResponse {

    private int code;
    private String msg;
    private MessageData data;

    @Data
    public static class MessageData {
        @JsonProperty("message_id")
        private String messageId;

        @JsonProperty("root_id")
        private String rootId;

        @JsonProperty("parent_id")
        private String parentId;

        @JsonProperty("thread_id")
        private String threadId;

        @JsonProperty("msg_type")
        private String msgType;

        @JsonProperty("create_time")
        private String createTime;

        @JsonProperty("update_time")
        private String updateTime;

        private boolean deleted;
        private boolean updated;

        @JsonProperty("chat_id")
        private String chatId;
        
        // 其他字段根据需要添加
    }
}
