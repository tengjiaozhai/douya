package com.tengjiao.douya.interfaces.web;

import com.tengjiao.douya.application.service.EatingMasterApp;


import com.alibaba.cloud.ai.graph.store.StoreItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能体
 *
 * @author tengjiao
 * @since 2025-12-04 14:56
 */
@RestController
@RequestMapping("/douya")
@Tag(name = "智能体")
@RequiredArgsConstructor
public class AiController {

    private final EatingMasterApp eatingMasterApp;

    @GetMapping("/hello")
    @Operation(summary = "健康监测")
    public String hello() {
        return "hello, ai";
    }

    @GetMapping("/chat")
    @Operation(summary = "与吃饭大师对话")
    public String chat(
            @Parameter(description = "用户消息") @RequestParam String message,
            @Parameter(description = "用户ID") @RequestParam(defaultValue = "user_001") String userId
    ) {
        return eatingMasterApp.ask(message, userId);
    }

    @GetMapping("/preferences")
    @Operation(summary = "获取用户偏好")
    public List<String> getPreferences(
            @Parameter(description = "用户ID") @RequestParam(defaultValue = "user_001") String userId
    ) {
        Optional<StoreItem> prefsOpt = eatingMasterApp.getMemoryStore().getItem(List.of("user_data"), userId + "_preferences");
        if (prefsOpt.isPresent()) {
            Map<String, Object> prefsData = prefsOpt.get().getValue();
            Object items = prefsData.get("items");
            if (items instanceof List) {
                return (List<String>) items;
            }
        }
        return new ArrayList<>();
    }
}
