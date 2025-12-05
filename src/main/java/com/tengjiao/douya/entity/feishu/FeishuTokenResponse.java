package com.tengjiao.douya.entity.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FeishuTokenResponse {
    private int code;
    private String msg;

    @JsonProperty("app_access_token")
    private String appAccessToken;

    private int expire;

    @JsonProperty("tenant_access_token")
    private String tenantAccessToken;
}
