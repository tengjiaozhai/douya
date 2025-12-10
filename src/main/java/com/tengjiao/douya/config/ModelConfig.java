package com.tengjiao.douya.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型配置
 *
 * @author tengjiao
 * @since 2025-12-10 18:46
 */
@Configuration
public class ModelConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Bean
    public ChatModel eatingMasterModel() {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
            .apiKey(apiKey)
            .build();
        return DashScopeChatModel.builder()
            .dashScopeApi(dashScopeApi)
            .defaultOptions(DashScopeChatOptions.builder()
                .withTemperature(0.5)
                .withMaxToken(1000)
                .withEnableSearch(true)
                .withTopP(0.9)
                .withModel("qwen-plus")
                .build())
            .build();
    }
}
