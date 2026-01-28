package com.tengjiao.douya.entity.feishu.content;



import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 飞书富文本消息发送请求体内容
 * 注意：发送时的结构是 {"zh_cn": {...}}，而 FeishuPostContent 只是内部的 {...}
 *
 * @author tengjiao
 * @since 2026-01-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuPostMessageContent {

    /**
     * 中文富文本内容
     */
    @SerializedName("zh_cn")
    @JsonProperty("zh_cn")
    private FeishuPostContent zhCn;
}
