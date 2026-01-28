package com.tengjiao.douya.entity.feishu;



import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
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
        @SerializedName("message_id")
        private String messageId;

        @SerializedName("root_id")
        private String rootId;

        @SerializedName("parent_id")
        private String parentId;

        @SerializedName("thread_id")
        private String threadId;

        @SerializedName("msg_type")
        private String msgType;

        @SerializedName("create_time")
        private String createTime;

        @SerializedName("update_time")
        private String updateTime;

        private boolean deleted;
        private boolean updated;

        @SerializedName("chat_id")
        private String chatId;

        // 其他字段根据需要添加
    }
}
