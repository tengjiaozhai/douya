package com.tengjiao.douya.entity.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * 飞书图片上传响应
 *
 * @author tengjiao
 * @since 2026-01-25
 */
@Data
public class FeishuImageUploadResponse {

    /**
     * 错误码，0 表示成功
     */
    private Integer code;

    /**
     * 错误信息
     */
    private String msg;

    /**
     * 响应数据
     */
    private Data data;

    @lombok.Data
    public static class Data {
        /**
         * 图片的 key
         */
        @SerializedName("image_key")
        @JsonProperty("image_key")
        private String imageKey;
    }
}
