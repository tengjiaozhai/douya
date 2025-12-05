package com.tengjiao.douya.controller;

import com.tengjiao.douya.entity.feishu.FeishuMessageSendResponse;
import com.tengjiao.douya.service.FeishuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 飞书相关接口
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@RestController
@RequestMapping("/douya/feishu")
@Tag(name = "飞书接口")
@RequiredArgsConstructor
public class FeishuController {

    private final FeishuService feishuService;

    @PostMapping("/token")
    @Operation(summary = "获取应用访问凭证 (app_access_token)")
    public String getAppAccessToken() {
        return feishuService.getAppAccessToken();
    }

    @PostMapping("/tenant-token")
    @Operation(summary = "获取租户访问凭证 (tenant_access_token)")
    public String getTenantAccessToken() {
        return feishuService.getTenantAccessToken();
    }

    @PostMapping("/message/send")
    @Operation(summary = "发送消息")
    public FeishuMessageSendResponse sendMessage(
            @RequestParam(defaultValue = "open_id") String receiveIdType,
            @RequestBody com.tengjiao.douya.entity.feishu.FeishuMessageSendRequest request) {
        return feishuService.sendMessage(receiveIdType, request);
    }
}
