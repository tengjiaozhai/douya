package com.tengjiao.douya.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书配置属性
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@Data
@Component
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {
    /**
     * 飞书应用 App ID
     */
    private String appId;

    /**
     * 飞书应用 App Secret
     */
    private String appSecret;

    /**
     * 获取 App Access Token 的 URL
     */
    private String appTokenUrl = "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal";

    /**
     * 获取 Tenant Access Token 的 URL
     */
    private String tenantTokenUrl = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
}
