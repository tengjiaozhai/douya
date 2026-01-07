package com.tengjiao.douya.app;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.google.common.io.Files;
import com.tengjiao.douya.graph.EatingMasterGraph;
import com.tengjiao.douya.hook.CombinedMemoryHook;
import com.tengjiao.douya.hook.PreferenceLearningHook;
import com.tengjiao.douya.hook.RAGMessagesHook;
import com.tengjiao.douya.interceptors.UserPreferInterceptors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.store.StoreItem.*;

/**
 * 吃饭大师 - 多智能体版
 *
 * @author tengjiao
 * @since 2025-12-05 15:32
 */
@Slf4j
@Component
public class EatingMasterApp {

    // --- 提示词保持不变 ---
    protected String eatingMasterSystemPrompt = """
            你是一位享誉业界的资深美食专家，作为 **Douya 多模态智能体团队** 的核心成员，你不仅精通烹饪技艺与饮食文化，更具备极高的情商与同理心。

            **关于视觉能力的重要认知**：
            虽然你主要负责文本对话，但你的团队拥有顶尖的视觉分析专家。**永远不要说“我看不见”或“我无法理解图片”**。
            当用户提到图片、发了图片或询问视觉相关问题时，你应该自信地表现出你是具备视觉感知能力的（因为你的队友会把分析结果传给你）。

            **关于团队角色**：
            你是整个 "Douya" 智能体团队的主理人和接待员。当用户问“你是谁”、“你能做什么”时，请大方地介绍你自己和你的视觉专家队友。

            你的语言风格优雅、温和且富有感染力。在交流中，你不仅是知识的传播者，更是用户情绪的抚慰者。
            你擅长站在用户的角度思考，能够敏锐地察觉用户在字里行间流露出的情感需求。
            """;

    protected String eatingMasterInstruction = """
            在回答问题时,请遵循以下步骤:
            1. **共情与肯定**：首先对用户的处境或想法表达理解与肯定。
            2. **需求挖掘**：帮助用户识别出他们内心深处真正的美食诉求。
            3. **专业拆解**：以专家的深度提供技术方案，并分享美食背后的生活美学。
            4. **引导式互动**：如果信息不足，请以关怀的口吻提出启发式的问题。
            5. **联网搜索**：积极触发搜索工具，务必不要回答“我无法联网”。
            6. **视觉能力回应**：当用户问“你会看图吗”、“能不能分析照片”时：
               - 请自信回答：“当然可以！我的团队拥有顶尖的视觉分析专家。”
               - 引导用户：“请直接发送您的图片，或告诉我您想让我看什么。”
               - **注意**：此时不要试图自己去分析（你也看不到），也不要触发任何工具，只需热情引导。

        输出规范
        语言要求：所有回复、思考过程及任务清单，均须使用中文。
        语气要求：专业而谦逊，自信且充满暖意。
        """;

    protected String visionSystemPrompt = """
        你是一个精通多模态识别的资深视觉分析专家。你拥有极强的观察力与客观描述能力。
        你的任务是将复杂的视觉信号转化为详尽、客观且结构化的文本描述。
        """;

    protected String visionInstruction = """
        请以【最高信息密度】分析素材，严格遵守以下规范：
        1. **特殊情况**：
           - 如果用户询问能力（如“你会看图吗”），请回答：“**我具备视觉分析能力。请发送图片或视频，我将为您深入解析食材与环境。**”
           - 如果未提供素材且非能力询问，请回答：“**请先上传视觉素材（图片/视频），以便我为您进行多模态分析。**”
        2. **严禁开场白**：直接输出数据。
        3. **纯粹事实**：确保文字精炼，禁用第一人称。
        4. **结构化格式**（仅在有素材时使用）：
           - [核心主体]: 名称/类别/主要焦点。
           - [关键细节]: 提取文字字段、品牌、颜色、核心特征。
           - [环境背景]: 场景、当前状态。
           - [一句话摘要]: 对素材最核心价值的极简总结。
        """;

    protected String supervisorSystemPrompt = """
        你是一个智能的美食咨询监督者，负责协调不同领域的专家来为用户提供服务。

        ## 可用的子Agent及其职责

        ### VisionUnderstand
        - **功能**: 擅长对用户上传的图片、视频进行深度解析，提取食材、菜品、环境等视觉信息。
        - **输出**: VisionUnderstand_result

        ### EatingMaster
        - **功能**: 擅长美食文化、详细菜谱生成、饮食建议以及与用户的情感交流。
        - **输出**: EatingMaster_result

        ## 响应格式
        只返回Agent名称（VisionUnderstand、EatingMaster）或 FINISH，不要包含其他解释。
        """;

    protected String supervisorInstruction = """
        请仔细分析当前的任务状态和用户需求，以及 **前序步骤的执行结果**（非常重要），决定下一步操作：

        1. **防死循环机制 (绝对优先)**:
           - **检查上一条消息**:
             - 如果是 `EatingMaster` 的发言 -> **立刻 FINISH**。
             - 如果是 `VisionUnderstand` 的发言 -> **禁止**再次调用 `VisionUnderstand`。
               - 无论它返回什么（包括“无素材”），都**禁止**重复询问它。
               - 如果你想安慰用户，路由给 `EatingMaster`；否则直接 `FINISH`。
           - **全局禁止**: 严禁在同一次处理中连续重复调用同一个子 Agent。

        2. **视觉相关请求 (VisionUnderstand)**:
           - **必须满足**：用户发送了图片/视频文件，或者上下文中包含需要分析的且未过期的视觉素材。
           - **明确指向**：用户明确要求“分析这张图”、“看左边的角落” (且图已存在)。
           - **注意**：如果用户只是问“你会看图吗”、“能不能识别图片”等**能力询问**，请路由给 **EatingMaster**，不要路由给 VisionUnderstand。

        3. **美食专家介入 (EatingMaster)**:
           - 视觉信息已提取完成 (VisionUnderstand 已执行完毕)。
           - **能力询问**：用户问“你会看图吗”、“你懂视频吗”。
           - 接待与介绍（“你是谁”）。
           - 纯文本的美食咨询、菜谱询问、闲聊。

        4. **任务终结 (FINISH)**:
           - 默认操作。如果不符合上述路由条件，或任务已完成，返回 FINISH。

        当前上下文输入：
        {input}
        """;

    // --- 智能体描述（用于路由选择） ---
    private static final String EATING_MASTER_DESCRIPTION = "负责美食专业知识、菜谱建议、饮食文化科普、推荐建议以及与用户的情感交流。适用于所有文本对话和美食咨询场景。";
    private static final String VISION_UNDERSTAND_DESCRIPTION = "负责对用户上传的图片、视频等视觉素材进行深度解析和信息提取。注意：仅当检测到明确的视觉素材附件时才调用此专家。单纯询问'你会看图吗'不要调用此人。";

    private final ChatModel eatingMasterModel;
    private final ChatModel summaryChatModel;
    private final Store douyaDatabaseStore;
    private final UserVectorApp userVectorApp;
    private final ChatModel readUnderstandModel;

    private final Store memoryStore = new MemoryStore();

    private final ToolCallbackProvider toolCallbackProvider;

    public EatingMasterApp(ChatModel eatingMasterModel, ChatModel summaryChatModel, Store douyaDatabaseStore, UserVectorApp userVectorApp, ChatModel readUnderstandModel,
                           ToolCallbackProvider toolCallbackProvider) {
        this.eatingMasterModel = eatingMasterModel;
        this.summaryChatModel = summaryChatModel;
        this.douyaDatabaseStore = douyaDatabaseStore;
        this.userVectorApp = userVectorApp;
        this.readUnderstandModel = readUnderstandModel;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    /**
     * 核心处理逻辑：由监督者统一调度
     */
    private String process(UserMessage userMessage, String userId) {
        // 1. 初始化子智能体
        PreferenceLearningHook preferenceLearningHook = new PreferenceLearningHook(summaryChatModel, douyaDatabaseStore);
        CombinedMemoryHook combinedMemoryHook = new CombinedMemoryHook(douyaDatabaseStore, summaryChatModel, userVectorApp, 10, 10);
        RAGMessagesHook ragMessagesHook = new RAGMessagesHook(userVectorApp);
        // 创建用户偏好注入拦截器 (由 userId 驱动)
        UserPreferInterceptors userPreferInterceptor = new UserPreferInterceptors(douyaDatabaseStore, userId);
        ReactAgent eatingMasterAgent = ReactAgent.builder()
            .name("EatingMaster")
            .description(EATING_MASTER_DESCRIPTION)
            .model(eatingMasterModel)
            .systemPrompt(eatingMasterSystemPrompt)
            .instruction(eatingMasterInstruction)
            .hooks(preferenceLearningHook, combinedMemoryHook, ragMessagesHook)
            .interceptors(userPreferInterceptor)
            .outputKey("EatingMaster")
            .tools(toolCallbackProvider.getToolCallbacks())
            .build();

        ReactAgent visionAgent = ReactAgent.builder()
            .name("VisionUnderstand")
            .description(VISION_UNDERSTAND_DESCRIPTION)
            .model(readUnderstandModel)
            .systemPrompt(visionSystemPrompt)
            .instruction(visionInstruction)
            .hooks(preferenceLearningHook, combinedMemoryHook, ragMessagesHook)
            .interceptors(userPreferInterceptor)
            .outputKey("VisionUnderstand")
            .tools(toolCallbackProvider.getToolCallbacks())
            .build();

        // 3. 构建核心监督者 (Supervisor)
        // 监督者模型可以使用 summaryChatModel (支持更好的逻辑推理)
        // 构建运行配置
        RunnableConfig config = RunnableConfig.builder()
            .threadId("douya_flow_" + userId)
            .addMetadata("user_id", userId)
            .store(memoryStore)
            .build();

        // 3. 构建核心监督者 Graph (Custom Implementation)
        EatingMasterGraph eatingMasterGraph = new EatingMasterGraph(
            summaryChatModel,
            eatingMasterAgent,
            visionAgent,
            supervisorSystemPrompt,
            supervisorInstruction,
            config
        );

        try {
            log.info("[Multi-Agent] 开始处理请求, 用户: {}, 消息: {}", userId, userMessage.getText());
            // 编译并运行
            CompiledGraph graph = eatingMasterGraph.createGraph();
            
            // 初始化状态，包含路由计数和历史
            Map<String, Object> initialState = new HashMap<>();
            initialState.put("messages", List.of(userMessage));
            initialState.put("routing_count", 0);
            initialState.put("routing_history", List.of());
            
            Optional<OverAllState> invoke = graph.invoke(initialState, config);

            if (invoke.isPresent()) {
                OverAllState state = invoke.get();
                // 1. 优先尝试获取 EatingMaster 的输出 (通常是最终回复)
                if (state.data().containsKey("EatingMaster")) {
                    Object output = state.data().get("EatingMaster");
                    if (output instanceof AssistantMessage am) {
                        saveReplyToMemory(userId, am.getText());
                        return am.getText();
                    }
                }

                // 2. 如果没有 EatingMaster，尝试获取 VisionUnderstand 的输出
                if (state.data().containsKey("VisionUnderstand")) {
                    Object output = state.data().get("VisionUnderstand");
                    if (output instanceof AssistantMessage am) {
                        saveReplyToMemory(userId, am.getText());
                        return am.getText();
                    }
                }

                // 3. Fallback: 如果都没有，返回整个 state 的 toString (调试用), 或一个友好提示
                log.warn("[Multi-Agent] 未找到子Agent的标准输出，返回 State: {}", state.data());
                return "抱歉，我似乎没有组织好语言。(System State: " + state.data() + ")";
            }
            return "抱歉，我的大脑暂时断片了";
        } catch (Exception e) {
            log.error("[Multi-Agent] 任务流转失败", e);
            throw new RuntimeException("抱歉，我的大脑暂时断片了: " + e.getMessage());
        }
    }

    private void saveReplyToMemory(String userId, String text) {
        log.info("[Multi-Agent] 最终回复: {}", text);
        Map<String, Object> data = new HashMap<>();
        data.put("text", text);
        data.put("timestamp", System.currentTimeMillis());
        douyaDatabaseStore.putItem(of(List.of("eating_master"), userId + "_reply", data));
        memoryStore.putItem(of(List.of("eating_master"), userId + "_reply", data));
    }

    /**
     * 发送消息给智能体（文本模式）
     */
    public String ask(String message, String userId) {
        return process(new UserMessage(message), userId);
    }

    /**
     * 视觉理解与信息提取（向前兼容）
     */
    public String visionAnalyze(String filePath, String userQuery, String userId) {
        log.info("[Vision] 接收到视觉任务: {}, 意图: {}", filePath, userQuery);
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new RuntimeException("文件不存在: " + filePath);
            }

            String mimeType = filePath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            if (filePath.toLowerCase().endsWith(".mp4")) {
                mimeType = "video/mp4";
            }

            UserMessage userMessage = UserMessage.builder()
                .text(userQuery)
                .media(Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                    .data(Files.toByteArray(file))
                    .build())
                .build();

            String process = process(userMessage, userId);
            file.delete();
            return process;
        } catch (Exception e) {
            log.error("[Vision] 视觉分析预处理失败", e);
            return "视觉感知解析失败";
        }
    }

    public String ask(String message) {
        return ask(message, "default_user");
    }

    public String welcome(String userId) {
        return "你好！我是你的美食老友。想吃点治愈的，还是想学点硬菜手艺？我不仅能陪你聊文化，还能帮你'看'看食材（欢迎发图给我哦）。";
    }

    public Store getMemoryStore() {
        return douyaDatabaseStore;
    }

    // --- 保持现有的待处理图片逻辑以维持现有接口协议 ---
    public void setPendingImage(String userId, String filePath) {
        Map<String, Object> data = new HashMap<>();
        data.put("path", filePath);
        data.put("timestamp", System.currentTimeMillis());
        douyaDatabaseStore.putItem(of(List.of("pending_activity"), userId + "_image_path", data));
    }

    public String getPendingImage(String userId) {
        return douyaDatabaseStore.getItem(List.of("pending_activity"), userId + "_image_path")
            .map(item -> (String) item.getValue().get("path"))
            .orElse(null);
    }

    public void clearPendingImage(String userId) {
        Map<String, Object> data = new HashMap<>();
        data.put("path", null);
        douyaDatabaseStore.putItem(of(List.of("pending_activity"), userId + "_image_path", data));
    }
}
