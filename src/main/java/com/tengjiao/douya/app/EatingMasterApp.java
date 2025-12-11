package com.tengjiao.douya.app;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.DatabaseStore;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.tengjiao.douya.hook.MessageSummarizationHook;
import com.tengjiao.douya.hook.PreferenceLearningHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

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

          在回答问题时,请:
          1. 首先理解用户的核心需求
          2. 分析可能的技术方案
          3. 提供清晰的建议和理由
          4. 如果需要更多信息,主动询问

          保持专业、暴躁、训斥、严厉的语气。
         \s
          输出规范 \s
          语言要求:所有回复、思考过程及任务清单,均须使用中文。
       \s""";

    private final ChatModel dashScopeChatModel;
    private final ChatModel summaryChatModel;
    private final Store douyaDatabaseStore;

    public EatingMasterApp(ChatModel eatingMasterModel, ChatModel summaryChatModel, Store douyaDatabaseStore) {
        this.dashScopeChatModel = eatingMasterModel;
        this.summaryChatModel = summaryChatModel;
        this.douyaDatabaseStore = douyaDatabaseStore;
    }

    /**
     * 发送消息给吃饭大师
     *
     * @param message 用户消息
     * @param userId  用户 ID
     * @return AI 回复
     */
    public String ask(String message, String userId) {
        // 创建偏好学习 Hook
        PreferenceLearningHook preferenceLearningHook = new PreferenceLearningHook(summaryChatModel, douyaDatabaseStore);
        MessageSummarizationHook messageSummarizationHook = new MessageSummarizationHook(summaryChatModel, 2000, 10);

        // 构建 Agent
        ReactAgent agent = ReactAgent.builder()
            .name("EatingMaster")
            .model(dashScopeChatModel)
            .instruction(SYSTEM_PROMPT)
            .hooks(preferenceLearningHook,messageSummarizationHook)
            .build();

        // 构建配置
        RunnableConfig config = RunnableConfig.builder()
            .threadId("eating_master_" + userId)
            .addMetadata("user_id", userId)
            .store(douyaDatabaseStore)
            .build();

        // UserMessage 输入
        try {
            UserMessage userMessage = new UserMessage(message);
            AssistantMessage response = agent.call(userMessage, config);
            log.info("EatingMaster: {}", response.getText());
            return response.getText();
        } catch (Exception e) {
            log.error("EatingMaster 调用失败", e);
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * 向后兼容的方法
     *
     * @param message 用户消息
     * @return AI 回复
     */
    public String ask(String message) {
        return ask(message, "default_user");
    }

    /**
     * 获取用户偏好存储
     *
     * @return 存储接口
     */
    public Store getMemoryStore() {
        return douyaDatabaseStore;
    }
}
