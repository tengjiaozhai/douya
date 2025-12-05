package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * 音频消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuAudioContent {
    /**
     * 文件唯一标识
     */
    @SerializedName("file_key")
    private String fileKey;

    /**
     * 音频时长，单位：毫秒
     */
    private Integer duration;
}
