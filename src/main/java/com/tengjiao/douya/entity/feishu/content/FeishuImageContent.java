package com.tengjiao.douya.entity.feishu.content;



import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
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
    @SerializedName("image_key")
    private String imageKey;
}
