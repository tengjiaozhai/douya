package com.tengjiao.douya.entity.feishu;



import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 飞书发送消息请求体
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuMessageSendRequest {

    /**
     * 消息接收者的 ID
     */
    @SerializedName("receive_id")
    @JsonProperty("receive_id")
    private String receiveId;

    /**
     * 消息类型 (text, post, image, interactive, etc.)
     */
    @SerializedName("msg_type")
    @JsonProperty("msg_type")
    private String msgType;

    /**
     * 消息内容，JSON 结构序列化后的字符串
     */
    @SerializedName("content")
    @JsonProperty("content")
    private String content;

    /**
     * 自定义设置的唯一字符串序列，用于去重
     */
    @SerializedName("uuid")
    @JsonProperty("uuid")
    private String uuid;
}
