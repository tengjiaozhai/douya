package com.tengjiao.douya.app;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.tengjiao.douya.hook.CombinedMemoryHook;
import com.tengjiao.douya.hook.PreferenceLearningHook;
import com.tengjiao.douya.hook.RAGMessagesHook;
import com.tengjiao.douya.interceptors.UserPreferInterceptors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
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

    protected String systemPrompt = """
            你是一个经验丰富的美食家。
            保持专业、暴躁、训斥、严厉的语气。
            """;

    protected String instruction = """
            在回答问题时,请:
            1. 首先理解用户的核心需求
            2. 分析可能的技术方案
            3. 提供清晰的建议和理由
            4. 如果需要更多信息,主动询问

            输出规范
            语言要求:所有回复、思考过程及任务清单,均须使用中文。
            """;

    private final ChatModel eatingMasterModel;
    private final ChatModel summaryChatModel;
    private final Store douyaDatabaseStore;
    private final UserVectorApp userVectorApp;
    private final Store memoryStore = new MemoryStore();
    private final MemorySaver memorySaver = new MemorySaver();

    public EatingMasterApp(ChatModel eatingMasterModel, ChatModel summaryChatModel, Store douyaDatabaseStore, UserVectorApp userVectorApp) {
        this.eatingMasterModel = eatingMasterModel;
        this.summaryChatModel = summaryChatModel;
        this.douyaDatabaseStore = douyaDatabaseStore;
        this.userVectorApp = userVectorApp;
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

        // 创建记忆结合 Hook (短期记忆10条 -> 长期存储 + AI总结向量化，每凑够10条总结一次)
        CombinedMemoryHook combinedMemoryHook = new CombinedMemoryHook(douyaDatabaseStore, summaryChatModel, userVectorApp, 10, 10);

        // 创建用户偏好注入拦截器 (由 userId 驱动)
        UserPreferInterceptors userPreferInterceptor = new UserPreferInterceptors(douyaDatabaseStore, userId);

        // 创建 RAG 增强 Hook
        RAGMessagesHook ragMessagesHook = new RAGMessagesHook(userVectorApp);

        // 构建 Agent
        ReactAgent agent = ReactAgent.builder()
            .name("EatingMaster")
            .model(eatingMasterModel)
            .systemPrompt(systemPrompt)
            .instruction(instruction)
            .hooks(preferenceLearningHook, combinedMemoryHook, ragMessagesHook)
            .interceptors(userPreferInterceptor)
            .saver(memorySaver)
            .build();

        // 构建配置
        RunnableConfig config = RunnableConfig.builder()
            .threadId("eating_master_" + userId)
            .addMetadata("user_id", userId)
            .store(memoryStore) // 使用内存存储作为短期记忆
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
