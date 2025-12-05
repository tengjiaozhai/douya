package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 富文本消息内容
 * 注意: 接收到的富文本结构与发送时不完全一致
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuPostContent {
    /**
     * 标题
     */
    private String title;

    /**
     * 内容，二维数组结构
     * 第一层数组表示段落，第二层数组表示段落内的元素
     */
    private List<List<PostElement>> content;

    @Data
    public static class PostElement {
        /**
         * 元素类型: text, a, at, img, media, emotion, hr, code_block
         */
        private String tag;

        // text 和 a 标签的字段
        private String text;
        private List<String> style;

        // a 标签的字段
        private String href;

        // at 标签的字段
        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("user_name")
        private String userName;

        // img 标签的字段
        @JsonProperty("image_key")
        private String imageKey;

        // media 标签的字段
        @JsonProperty("file_key")
        private String fileKey;

        // emotion 标签的字段
        @JsonProperty("emoji_type")
        private String emojiType;

        // code_block 标签的字段
        private String language;

        // text 标签的字段
        @JsonProperty("un_escape")
        private Boolean unEscape;
    }
}
