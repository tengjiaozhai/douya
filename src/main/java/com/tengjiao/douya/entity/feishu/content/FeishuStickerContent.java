package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 表情包消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuStickerContent {
    /**
     * 文件唯一标识
     * 注意: 机器人可以使用 file_key 发送消息，但不支持下载该表情包
     */
    @JsonProperty("file_key")
    private String fileKey;
}
