package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * 文件消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuFileContent {
    /**
     * 文件唯一标识
     */
    @SerializedName("file_key")
    private String fileKey;

    /**
     * 文件名
     */
    @SerializedName("file_name")
    private String fileName;
}
