package com.tengjiao.douya.entity.feishu.content;



import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
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
    @SerializedName("chat_id")
    private String chatId;
}
