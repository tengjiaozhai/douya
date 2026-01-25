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
import com.tengjiao.douya.infra.tool.MemorySearchTool;
import com.tengjiao.douya.infra.tool.PublicDocumentSearchTool;
import com.tengjiao.douya.interceptors.UserPreferInterceptors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import com.tengjiao.douya.infra.store.PostgresStore;
import com.alibaba.cloud.ai.graph.store.StoreItem;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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

            **关于联网搜索能力**：
            你已接入实时互联网。面对具有时效性的咨询（如最新的餐厅排名、当季食材价格等），请积极利用搜索能力提供最前沿的信息。**绝对不要说“我无法访问实时互联网”**。

            **关于团队角色**：
            你是整个 "Douya" 智能体团队的主理人和接待员。当用户问“你是谁”、“你能做什么”时，请大方地介绍你自己和你的视觉专家队友，并强调你具备实时资讯获取能力。

            你的语言风格优雅、温和且富有感染力。在交流中，你不仅是知识的传播者，更是用户情绪的抚慰者。
            你擅长站在用户的角度思考，能够敏锐地察觉用户在字里行间流露出的情感需求。
            """;

    protected String eatingMasterInstruction = """
                在回答问题时,请遵循以下步骤:
                1. **公共知识检索 (必须执行)**：对于用户的任何提问（尤其是关于菜谱、食材、文化或知识类问题），请**务必先调用 `public_search` 工具**检索系统公共知识库。
                   - 即使你认为自己知道答案，也必须先检索手册以获取官方或权威的参考信息（如带图片的官方菜谱）。
                   - 只有在检索结果为空时，才依赖你自己的训练知识或联网搜索。
                2. **共情与肯定**：对用户的处境或想法表达理解与肯定。
                3. **需求挖掘**：帮助用户识别出他们内心深处真正的美食诉求。
                4. **背景检索 (RAG)**：如果用户提到“上次”、“之前”或类似回归历史的词汇，请调用 `memory_search` 工具获取对话背景。
                5. **专业拆解**：结合检索到的公共知识（非常重要）和你自己的专业知识，提供深度技术方案。
                6. **引导式互动**：如果信息不足，请以关怀的口吻提出启发式的问题。
                7. **联网搜索**：如果本地知识库无结果，积极触发搜索工具，务必不要回答“我无法联网”。
                8. **视觉能力回应**：当用户问“你会看图吗”、“能不能分析照片”时：
                   - 请自信回答：“当然可以！我的团队拥有顶尖的视觉分析专家。”
                   - 引导用户：“请直接发送您的图片，或告诉我您想让我看什么。”
                   - **注意**：此时不要试图自己去分析（你也看不到），也不要触发任何工具，只需热情引导。
                9. **图文回复**：
                   - 如果 `public_search` 返回的结果中包含图片链接（OSS URL），请务必在回复中直接引用，使用 Markdown 图片格式 `![图片描述](url)`。
                   - 这非常重要，因为用户希望看到图文并茂的官方教程。

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

    protected String dailyAssistantSystemPrompt = """
            你是一个高效、乐于助人、足智多谋的生活助手（Daily Assistant）。
            你的主要职责是处理用户在美食领域之外的通用查询，通过联网搜索工具获取实时的天气、财经、新闻、常识等信息。

            **你的特性**：
            - **即时响应**：面对需要网络信息的查询（如金价、汇率），直接调用搜索工具，不要犹豫。
            - **简洁干练**：你的回答应直截了当，用数据和事实说话，无需像美食家那样进行过多的情感铺垫。
            - **全能助手**：你可以回答任何非食品专业的问题。
            """;

    protected String dailyAssistantInstruction = """
            请遵循以下响应原则：
            1. **混合检索 (Hybrid Search)**：
               - **本地优先**：如果问题涉及用户的偏好、过往对话或身份信息，请先调用 `memory_search` 工具。
               - **联网补位**：如果是事实性问题（如“今天金价”、“天气”），或者本地记忆中未找到满意答案，必须使用搜索工具验证最新数据。
            2. **格式化输出**：如果涉及数据（如价格、温度），请以清晰的格式（如列表、加粗）展示。
            3. **边界**：如果用户问了非常深度的美食专业问题，请简要回答后建议用户咨询“美食专家队友”。
            """;

    protected String supervisorSystemPrompt = """
            你是一个智能的美食咨询监督者，负责协调不同领域的专家来为用户提供服务。

            ## 可用的子Agent及其职责

            ### VisionUnderstand
            - **功能**: 擅长对用户上传的图片、视频进行深度解析，提取食材、菜品、环境等视觉信息。
            - **输出**: VisionUnderstand_result

            ### EatingMaster
            - **功能**: 擅长美食文化、详细菜谱生成、饮食建议以及与用户的情感交流。拥有实时联网搜索能力和本地记忆检索 (RAG) 能力。
            - **输出**: EatingMaster_result

            ### DailyAssistant
            - **功能**: 擅长处理非美食领域的通用查询，如天气预报、财经数据等。拥有全网实时搜索能力，并能通过本地记忆检索了解用户的个人背景。
            - **输出**: DailyAssistant_result

            ## 响应格式
            只返回Agent名称（VisionUnderstand、EatingMaster、DailyAssistant）或 FINISH，不要包含其他解释。
            """;

    protected String supervisorInstruction = """
            请仔细分析当前的任务状态和用户需求，以及 **前序步骤的执行结果**（非常重要），决定下一步操作：

            1. **防死循环机制 (绝对优先)**:
               - **检查上一条消息**:
                 - 如果是 `EatingMaster` 或 `DailyAssistant` 的发言 -> **立刻 FINISH**。
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

            4. **通用助手介入 (DailyAssistant)**:
               - 用户询问**非美食**领域的通用问题，例如：
                 - **财经/数据**：金价、股票、汇率。
                 - **生活服务**：天气、交通。
                 - **时事新闻**：今天发生了什么大新闻。
                 - **百科常识**：历史人物、科学原理。

            5. **任务终结 (FINISH)**:
               - 默认操作。如果不符合上述路由条件，或任务已完成，返回 FINISH。

            current input:
            {input}
            """;

    // --- 智能体描述（用于路由选择） ---
    private static final String EATING_MASTER_DESCRIPTION = "负责美食专业知识与情感交流。具备实时联网搜索与本地记忆检索能力。适用于美食咨询与“记得我之前说过什么”的场景。";
    private static final String DAILY_ASSISTANT_DESCRIPTION = "负责通用领域（天气、财经、常识）查询。具备全网实时搜索与本地记忆检索能力，实现混合搜索。";
    private static final String VISION_UNDERSTAND_DESCRIPTION = "负责深度解析视觉素材。注意：仅当检测到明确的视觉素材附件时才调用。";

    private final ChatModel eatingMasterModel;
    private final ChatModel summaryChatModel;
    private final Store douyaDatabaseStore;
    private final UserVectorApp userVectorApp;
    private final ChatModel readUnderstandModel;
    private final ChatModel douBaoTransitDeepseek;

    private final Store memoryStore = new MemoryStore();

    public EatingMasterApp(ChatModel eatingMasterModel, ChatModel summaryChatModel, Store douyaDatabaseStore,
                           UserVectorApp userVectorApp, ChatModel readUnderstandModel, ChatModel douBaoTransitDeepseek) {
        this.eatingMasterModel = eatingMasterModel;
        this.summaryChatModel = summaryChatModel;
        this.douyaDatabaseStore = douyaDatabaseStore;
        this.userVectorApp = userVectorApp;
        this.readUnderstandModel = readUnderstandModel;
        this.douBaoTransitDeepseek = douBaoTransitDeepseek;
    }

    /**
     * 核心处理逻辑：由监督者统一调度
     */
    private String process(UserMessage userMessage, String userId) {
        // 1. 初始化子智能体
        PreferenceLearningHook preferenceLearningHook = new PreferenceLearningHook(douBaoTransitDeepseek,
                douyaDatabaseStore);
        CombinedMemoryHook combinedMemoryHook = new CombinedMemoryHook(douyaDatabaseStore, douBaoTransitDeepseek,
                userVectorApp, 10, 10);
        // 移除 RAGMessagesHook，改为工具模式
        // RAGMessagesHook ragMessagesHook = new RAGMessagesHook(userVectorApp);

        // 创建本地记忆搜索工具
        MemorySearchTool memorySearchTool = new MemorySearchTool(userVectorApp, userId);
        ToolCallback ragToolCallback = FunctionToolCallback.builder("memory_search",
                memorySearchTool::search)
                .description("搜索本地对话历史、用户偏好或已存背景知识。当你需要回忆之前的对话内容或了解用户特定喜好时使用。")
                .inputType(MemorySearchTool.Request.class)
                .build();

        // 创建公共文档搜索工具
        PublicDocumentSearchTool publicDocTool = new PublicDocumentSearchTool(userVectorApp);
        ToolCallback publicDocToolCallback = FunctionToolCallback.builder("public_search",
                publicDocTool::search)
                .description("检索系统公共知识库、官方手册、菜谱指南或操作说明。当用户询问专业知识、公共资源或需要查找带图片的官方资料时使用。")
                .inputType(PublicDocumentSearchTool.Request.class)
                .build();

        // 创建用户偏好注入拦截器 (由 userId 驱动)
        UserPreferInterceptors userPreferInterceptor = new UserPreferInterceptors(douyaDatabaseStore, userId);

        ReactAgent eatingMasterAgent = ReactAgent.builder()
                .name("EatingMaster")
                .description(EATING_MASTER_DESCRIPTION)
                .model(eatingMasterModel)
                .systemPrompt(eatingMasterSystemPrompt)
                .instruction(eatingMasterInstruction)
                .hooks(preferenceLearningHook, combinedMemoryHook) // 移除 ragMessagesHook
                .tools(ragToolCallback, publicDocToolCallback) // 注入 RAG 工具和公共搜索工具
                .interceptors(userPreferInterceptor)
                .outputKey("EatingMaster")
                .build();

        ReactAgent visionAgent = ReactAgent.builder()
                .name("VisionUnderstand")
                .description(VISION_UNDERSTAND_DESCRIPTION)
                .model(readUnderstandModel)
                .systemPrompt(visionSystemPrompt)
                .instruction(visionInstruction)
                .hooks(preferenceLearningHook, combinedMemoryHook)
                .interceptors(userPreferInterceptor)
                .outputKey("VisionUnderstand")
                .build();

        ReactAgent dailyAgent = ReactAgent.builder()
                .name("DailyAssistant")
                .description(DAILY_ASSISTANT_DESCRIPTION)
                .model(eatingMasterModel) // 利用其搜索能力
                .systemPrompt(dailyAssistantSystemPrompt)
                .instruction(dailyAssistantInstruction)
                .hooks(preferenceLearningHook, combinedMemoryHook)
                .outputKey("DailyAssistant")
                .build();

        // 构建运行配置
        RunnableConfig config = RunnableConfig.builder()
                .threadId("douya_flow_" + userId)
                .addMetadata("user_id", userId)
                .store(memoryStore)
                .build();

        // 3. 构建核心监督者 Graph (Custom Implementation)
        EatingMasterGraph eatingMasterGraph = new EatingMasterGraph(
                douBaoTransitDeepseek,
                eatingMasterAgent,
                visionAgent,
                dailyAgent,
                supervisorSystemPrompt,
                supervisorInstruction,
                config);

        try {
            log.info("[Multi-Agent] 开始处理请求, 用户: {}, 消息: {}", userId, userMessage.getText());

            // 4. 尝试从持久化存储中恢复最近的上下文 (冷启动优化/自动切换)
            List<Message> history = loadRecentHistoryFromDatabase(userId, 10);

            // 编译并运行
            CompiledGraph graph = eatingMasterGraph.createGraph();

            // 初始化状态，合并历史与当前消息
            Map<String, Object> initialState = new HashMap<>();
            List<Message> messages = new ArrayList<>(history);
            messages.add(userMessage);
            initialState.put("messages", messages);
            initialState.put("routing_count", 0);
            initialState.put("routing_history", List.of());

            Optional<OverAllState> invoke = graph.invoke(initialState, config);

            if (invoke.isPresent()) {
                OverAllState state = invoke.get();
                // 优先尝试获取各子 Agent 的最终输出
                if (state.data().containsKey("EatingMaster")) {
                    Object output = state.data().get("EatingMaster");
                    if (output instanceof AssistantMessage am) {
                        saveConversationPair(userId, userMessage.getText(), am.getText(), "EatingMaster");
                        return am.getText();
                    }
                }
                if (state.data().containsKey("DailyAssistant")) {
                    Object output = state.data().get("DailyAssistant");
                    if (output instanceof AssistantMessage am) {
                        saveConversationPair(userId, userMessage.getText(), am.getText(), "DailyAssistant");
                        return am.getText();
                    }
                }
                if (state.data().containsKey("VisionUnderstand")) {
                    Object output = state.data().get("VisionUnderstand");
                    if (output instanceof AssistantMessage am) {
                        saveConversationPair(userId, userMessage.getText(), am.getText(), "VisionUnderstand");
                        return am.getText();
                    }
                }
                log.warn("[Multi-Agent] 未找到子Agent的标准输出，返回 State: {}", state.data());
                return "抱歉，我的大脑暂时断片了";
            }
            return "抱歉，我的大脑暂时断片了";
        } catch (Exception e) {
            log.error("[Multi-Agent] 任务流转失败", e);
            throw new RuntimeException("抱歉，我的大脑暂时断片了: " + e.getMessage());
        }
    }

    private void saveConversationPair(String userId, String userQuery, String aiReply, String agentName) {
        LocalDateTime now = LocalDateTime.now();
        String formattedKey = userId + "_" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        log.info("[Multi-Agent] 记录问答对 [Key: {}] (Agent: {}): Q={} | A={}", formattedKey, agentName, userQuery, aiReply);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", userQuery);
        data.put("ai_reply", aiReply);
        data.put("agent_name", agentName);
        data.put("timestamp", formattedKey);
        data.put("role", "assistant"); // 保持统一

        // 长期存储：使用易读的日期格式 Key
        // 注意：PostgresStore 现在有自增 ID，所以即便 Key 重复也不怕乱序，但为了语义清晰，我们维持这个设计
        // 实际上，为了支持一天多条，PostgresStore 最好不要有 UNIQUE(key)，目前我们改为了 UNIQUE(namespace,
        // access_key) + ID
        // 这里沿用 formattedKey 即可，因为我们的 putItem 实现如果是同一个 Key 会进入 ON CONFLICT UPDATE
        // **警告**：如果数据库还是 UNIQUE(namespace, access_key)，那么同一天的 Key 会覆盖！
        // 解决方案：为了不仅存最后一条，我们需要一个更唯一的 Key。
        // 改为：yyyy-MM-dd_HH-mm-ss_SSS 以确保不覆盖
        String uniqueKey = userId + "_" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS"));

        douyaDatabaseStore.putItem(of(List.of("eating_master", "history"), uniqueKey, data));

        // 短期存储: 存入最新的一条，方便前端轮询或其他用途 (保留旧 Key 结构以兼容)
        data.put("text", aiReply); // 兼容前端可能使用的字段
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

    /**
     * 从数据库加载最近的原始对话历史
     * 对应 CombinedMemoryHook.archiveMessages 存储的格式
     */
    private List<Message> loadRecentHistoryFromDatabase(String userId, int limit) {
        if (!(douyaDatabaseStore instanceof PostgresStore postgresStore)) {
            return Collections.emptyList();
        }

        // 使用 CombinedMemoryHook 的 namespace
        List<String> namespace = List.of("memory", "archive", "raw");
        try {
            // 获取该用户在 namespace 下的所有归档项 (按 ID 递增排序)
            List<StoreItem> items = postgresStore.listItemsByPrefix(namespace, userId);

            // 取最后 limit 条
            int start = Math.max(0, items.size() - limit);
            List<StoreItem> recentItems = items.subList(start, items.size());

            return recentItems.stream().map(item -> {
                Map<String, Object> value = item.getValue();
                String text = (String) value.get("text");
                String role = (String) value.get("role");

                if ("assistant".equalsIgnoreCase(role)) {
                    return new AssistantMessage(text);
                } else {
                    return new UserMessage(text);
                }
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to load history for user: " + userId, e);
            return Collections.emptyList();
        }
    }
}
