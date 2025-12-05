package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 视频消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuMediaContent {
    /**
     * 文件唯一标识
     */
    @JsonProperty("file_key")
    private String fileKey;

    /**
     * 视频封面图片的唯一标识
     */
    @JsonProperty("image_key")
    private String imageKey;

    /**
     * 文件名
     */
    @JsonProperty("file_name")
    private String fileName;

    /**
     * 视频时长，单位：毫秒
     */
    private Integer duration;
}
