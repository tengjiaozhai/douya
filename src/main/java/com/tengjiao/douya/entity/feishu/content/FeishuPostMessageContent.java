package com.tengjiao.douya.entity.feishu.content;



import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /**
     * 英文富文本内容（接收时兼容）
     */
    @SerializedName("en_us")
    @JsonProperty("en_us")
    private FeishuPostContent enUs;

    /**
     * 接收 post 消息时可能是扁平结构：{"title":"...", "content":[...]}
     */
    private String title;

    /**
     * 接收 post 消息时可能是扁平结构
     */
    private List<List<FeishuPostContent.PostElement>> content;

    /**
     * 统一不同结构，拿到可消费的 post 内容
     */
    public FeishuPostContent resolvePostContent() {
        if (zhCn != null) {
            return zhCn;
        }
        if (enUs != null) {
            return enUs;
        }
        if (content == null && (title == null || title.isBlank())) {
            return null;
        }
        FeishuPostContent resolved = new FeishuPostContent();
        resolved.setTitle(title);
        resolved.setContent(content);
        return resolved;
    }
}
