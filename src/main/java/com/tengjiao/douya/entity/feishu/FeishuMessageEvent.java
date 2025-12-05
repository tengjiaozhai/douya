package com.tengjiao.douya.entity.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * 飞书消息事件实体类
 * 对应 im.message.receive_v1 事件的 event 部分
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
@ToString
public class FeishuMessageEvent {

    /**
     * 事件的发送者
     */
    private Sender sender;

    /**
     * 事件中包含的消息内容
     */
    private Message message;

    @Data
    public static class Sender {
        /**
         * 发送者 ID
         */
        @JsonProperty("sender_id")
        private UserId senderId;

        /**
         * 消息发送者类型。目前只支持用户(user)发送的消息。
         */
        @JsonProperty("sender_type")
        private String senderType;

        /**
         * tenant key
         */
        @JsonProperty("tenant_key")
        private String tenantKey;
    }

    @Data
    public static class UserId {
        /**
         * 用户的 union id
         */
        @JsonProperty("union_id")
        private String unionId;

        /**
         * 用户的 user id
         */
        @JsonProperty("user_id")
        private String userId;

        /**
         * 用户的 open id
         */
        @JsonProperty("open_id")
        private String openId;
    }

    @Data
    public static class Message {
        /**
         * 消息 ID
         */
        @JsonProperty("message_id")
        private String messageId;

        /**
         * 根消息 ID
         */
        @JsonProperty("root_id")
        private String rootId;

        /**
         * 父消息 ID
         */
        @JsonProperty("parent_id")
        private String parentId;

        /**
         * 消息发送时间（毫秒）
         */
        @JsonProperty("create_time")
        private String createTime;

        /**
         * 消息更新时间（毫秒）
         */
        @JsonProperty("update_time")
        private String updateTime;

        /**
         * 消息所在的群组 ID
         */
        @JsonProperty("chat_id")
        private String chatId;

        /**
         * 消息所属的话题 ID
         */
        @JsonProperty("thread_id")
        private String threadId;

        /**
         * 消息所在的群组类型
         * p2p: 单聊, group: 群组
         */
        @JsonProperty("chat_type")
        private String chatType;

        /**
         * 消息类型
         */
        @JsonProperty("message_type")
        private String messageType;

        /**
         * 消息内容，JSON 结构序列化后的字符串
         */
        private String content;

        /**
         * 被提及用户的信息
         */
        private List<Mention> mentions;

        /**
         * 用户代理数据
         */
        @JsonProperty("user_agent")
        private String userAgent;
    }

    @Data
    public static class Mention {
        /**
         * 被提及用户序号
         */
        private String key;

        /**
         * 被提及用户 ID
         */
        private UserId id;

        /**
         * 被提及用户姓名
         */
        private String name;

        /**
         * tenant key
         */
        @JsonProperty("tenant_key")
        private String tenantKey;
    }
}
