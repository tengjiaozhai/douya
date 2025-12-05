package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty("file_key")
    private String fileKey;

    /**
     * 文件名
     */
    @JsonProperty("file_name")
    private String fileName;
}
