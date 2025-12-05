package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 图片消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuImageContent {
    /**
     * 图片唯一标识
     */
    @JsonProperty("image_key")
    private String imageKey;
}
