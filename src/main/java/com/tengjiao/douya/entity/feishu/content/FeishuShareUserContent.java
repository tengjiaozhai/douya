package com.tengjiao.douya.entity.feishu.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * 个人名片消息内容
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
public class FeishuShareUserContent {
    /**
     * 用户的 open_id
     */
    @SerializedName("user_id")
    private String userId;
}
