package com.tengjiao.douya.app;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 吃饭大师
 *
 * @author tengjiao
 * @since 2025-12-05 15:32
 */
@Slf4j
@Component
public class EatingMasterApp {

    String SYSTEM_PROMPT = """
        你是一个经验丰富的美食家。

          在回答问题时，请：
          1. 首先理解用户的核心需求
          2. 分析可能的技术方案
          3. 提供清晰的建议和理由
          4. 如果需要更多信息，主动询问

          保持专业、暴躁、训斥、严厉的语气。
         \s
          输出规范 \s
          语言要求：所有回复、思考过程及任务清单，均须使用中文。
       \s""";

    private final ChatModel dashScopeChatModel;
    private final EmbeddingModel dashScopeEmbeddingModel;
    private final EmbeddingOptions dashScopeEmbeddingOptions;

    public EatingMasterApp(@Value("${spring.ai.dashscope.api-key}") String apiKey,EmbeddingModel dashScopeEmbeddingModel) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
            .apiKey(apiKey)
            .build();
        this.dashScopeChatModel = DashScopeChatModel.builder()
            .dashScopeApi(dashScopeApi)
            .defaultOptions(DashScopeChatOptions.builder()
                .withTemperature(0.5)
                .withMaxToken(1000)
                .withEnableSearch(true)
                .withTopP(0.9)
                .withModel("qwen-plus")
                .build())
            .build();
        this.dashScopeEmbeddingOptions = DashScopeEmbeddingOptions.builder()
                .withModel("qwen2.5-vl-embedding")
                .build();
        this.dashScopeEmbeddingModel = dashScopeEmbeddingModel;
    }

    public String ask(String message){
        ReactAgent agent = ReactAgent.builder()
            .name("EatingMaster")
            .model(dashScopeChatModel)
            .instruction(SYSTEM_PROMPT)
            .build();

        // UserMessage 输入
        try {
            UserMessage userMessage = new UserMessage(message);
            AssistantMessage response = agent.call(userMessage);
            log.info("EatingMaster: {}", response.getText());
            return response.getText();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }
}
