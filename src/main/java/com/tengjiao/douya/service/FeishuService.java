package com.tengjiao.douya.service;

import com.tengjiao.douya.entity.feishu.FeishuMessageSendRequest;
import com.tengjiao.douya.entity.feishu.FeishuMessageSendResponse;

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

    /**
     * 发送消息
     *
     * @param receiveIdType 接收者 ID 类型 (open_id, user_id, union_id, email, chat_id)
     * @param request       发送消息请求体
     * @return 响应结果
     */
    FeishuMessageSendResponse sendMessage(String receiveIdType, FeishuMessageSendRequest request);
}
