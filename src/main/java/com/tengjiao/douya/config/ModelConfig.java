package com.tengjiao.douya.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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
    @Value("${spring.ai.deepseek.api-key}")
    private String deepseekApiKey;
    @Value("${spring.ai.openai.api-key}")
    private String douBaoApiKey;

    @Bean
    public ChatModel eatingMasterModel() {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .temperature(0.5)
                        .maxToken(2000)
                        .enableSearch(true)
                        .topP(0.9)
                        .model("qwen-plus")
                        .build())
                .build();
    }

    @Bean
    public ChatModel summaryChatModel() {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(deepseekApiKey)
                .build();
        DeepSeekChatOptions deepSeekChatOptions = DeepSeekChatOptions.builder()
                .model(DeepSeekApi.ChatModel.DEEPSEEK_REASONER.getValue())
                .maxTokens(2000)
                .temperature(0.5)
                .topP(0.9)
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(deepSeekChatOptions)
                .build();
    }

    // 全模态模型
    @Bean
    public ChatModel readUnderstandModel() {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("Authorization", "Bearer " + douBaoApiKey);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://ark.cn-beijing.volces.com/api/v3/chat/completions")
                .headers(headers)
                .apiKey(douBaoApiKey)
                .build();
        OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
                .model("Doubao-Seed-1.8")
                .maxTokens(2000)
                .temperature(0.5)
                .topP(0.9)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(openAiChatOptions)
                .build();
    }
}
