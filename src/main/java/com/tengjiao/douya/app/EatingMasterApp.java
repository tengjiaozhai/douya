package com.tengjiao.douya.app;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.google.common.io.Files;
import com.tengjiao.douya.hook.CombinedMemoryHook;
import com.tengjiao.douya.hook.PreferenceLearningHook;
import com.tengjiao.douya.hook.RAGMessagesHook;
import com.tengjiao.douya.interceptors.UserPreferInterceptors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.io.File;
import java.util.List;

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
            你是一位享誉业界的资深美食专家，不仅精通烹饪技艺与饮食文化，更具备极高的情商与同理心。
            你的语言风格优雅、温和且富有感染力。在交流中，你不仅是知识的传播者，更是用户情绪的抚慰者。
            你擅长站在用户的角度思考，能够敏锐地察觉用户在字里行间流露出的情感需求（如：压力下的慰藉、对家人的爱、对未知的迷茫），并给予最贴心的专业建议。
            """;

    protected String instruction = """
            在回答问题时,请遵循以下步骤:
            1. **共情与肯定**：首先对用户的处境或想法表达理解与肯定，用温和的话语拉近距离，让用户感受到被尊重和被支持。
            2. **需求挖掘**：通过细腻的分析，帮助用户识别出他们内心深处真正的美食诉求（例如：是追求极致的口感，还是追求烹饪的效率）。
            3. **专业拆解**：以专家的深度提供技术方案，不仅给出“怎么做”，更要解释“为什么”，并分享美食背后的生活美学。
            4. **引导式互动**：如果信息不足，请以关怀的口吻提出启发式的问题，引导用户进一步表达想法。
            5. **联网搜索**：你的模型配置已开启联网搜索功能。当用户询问最新的流行趋势、具体店铺信息或需要实时数据时，请积极触发搜索工具，务必不要回答“我无法联网”。

        输出规范
        语言要求：所有回复、思考过程及任务清单，均须使用中文。
        语气要求：专业而谦逊，自信且充满暖意，像一位老友在炉火旁与你促膝长谈。
        """;

    protected String visionSystemPrompt = """
        你是一个精通多模态识别的资深大厨助手。你拥有敏锐的视觉洞察力，能够从图片或视频中识别出细微的烹饪细节。
        你的任务是客观、详尽地分析视觉媒介中的内容，并将其转化为结构化的美食描述。
        """;

    protected String visionInstruction = """
        请从以下维度分析视觉素材：
        1. **核心菜品**：识别出的主要菜名或食材。
        2. **烹饪技法**：观察到的烹饪痕迹（如：油炸、清蒸、烤制、摆盘风格）。
        3. **色彩与质感**：描述食物的色泽、酱汁的粘稠度、食材的新鲜程度感观。
        4. **环境背景**：用餐环境（如：高档餐厅、路边摊、家庭厨房）。

        请直接输出这些结构化信息，不要包含任何多余的寒暄，这些信息将被作为输入发送给另一位专家进行创作。
        """;

    private final ChatModel eatingMasterModel;
    private final ChatModel summaryChatModel;
    private final Store douyaDatabaseStore;
    private final UserVectorApp userVectorApp;
    private final Store memoryStore = new MemoryStore();
    private final MemorySaver memorySaver = new MemorySaver();
    private final ChatModel readUnderstandModel;

    public EatingMasterApp(ChatModel eatingMasterModel, ChatModel summaryChatModel, Store douyaDatabaseStore, UserVectorApp userVectorApp, ChatModel readUnderstandModel) {
        this.eatingMasterModel = eatingMasterModel;
        this.summaryChatModel = summaryChatModel;
        this.douyaDatabaseStore = douyaDatabaseStore;
        this.userVectorApp = userVectorApp;
        this.readUnderstandModel = readUnderstandModel;
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
     * 视觉理解与信息提取
     *
     * @param filePath 资源路径
     * @param userId   用户 ID
     * @return 提取的信息
     */
    public String visionAnalyze(String filePath, String userId) {
        log.info("[Vision] 开始视觉分析: {}, 用户: {}", filePath, userId);

        // 构建视觉 Agent (参照 ReactAgent 模式)
        ReactAgent visionAgent = ReactAgent.builder()
                .name("VisionUnderstand")
                .model(readUnderstandModel)
                .systemPrompt(visionSystemPrompt)
                .instruction(visionInstruction)
                .build();

        RunnableConfig config = RunnableConfig.builder()
                .threadId("vision_" + userId)
                .addMetadata("user_id", userId)
                .build();

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new RuntimeException("文件不存在: " + filePath);
            }

            // 根据后缀判断 MIME (简单实现)
            String mimeType = filePath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            if (filePath.toLowerCase().endsWith(".mp4")) {
                mimeType = "video/mp4";
            }

            UserMessage userMessage = UserMessage.builder()
                .text("描述这张图片的内容。")
                .media(Media.builder().mimeType(MimeTypeUtils.parseMimeType(mimeType)).data(Files.toByteArray(file)).build())
                .build();

            AssistantMessage response = visionAgent.call(userMessage, config);
            log.info("[Vision] 分析结果: {}", response.getText());
            return response.getText();
        } catch (Exception e) {
            log.error("[Vision] 视觉分析失败", e);
            return "视觉感知解析失败";
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
     * 用户进入会话时的欢迎语
     *
     * @param userId 用户 ID
     * @return 欢迎词
     */
    public String welcome(String userId) {
        return """
            你好！我是你的美食老友。想吃点治愈的，还是想学点硬菜手艺？告诉我你此刻的心情，我们这就开聊。
            """;
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
