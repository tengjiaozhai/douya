package com.tengjiao.douya.service;

/**
 * 飞书服务接口
 *
 * @author tengjiao
 * @since 2025-12-05
 */
public interface FeishuService {

    /**
     * 获取飞书应用访问凭证 (app_access_token)
     *
     * @return app_access_token
     */
    String getAppAccessToken();

    /**
     * 获取飞书租户访问凭证 (tenant_access_token)
     *
     * @return tenant_access_token
     */
    String getTenantAccessToken();
}
