package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 群名片消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuShareChatContent {
    /**
     * 群 ID
     */
    @JsonProperty("chat_id")
    private String chatId;
}
