package com.tengjiao.douya.application.service;

import com.tengjiao.douya.application.graph.EatingMasterGraph;
import com.tengjiao.douya.application.hook.CombinedMemoryHook;
import com.tengjiao.douya.application.hook.PreferenceLearningHook;
import com.tengjiao.douya.application.interceptors.UserPreferInterceptors;
import com.tengjiao.douya.infrastructure.persistence.PostgresStore;
import com.tengjiao.douya.infrastructure.tool.MemorySearchTool;
import com.tengjiao.douya.infrastructure.tool.PublicDocumentSearchTool;
import com.tengjiao.douya.infrastructure.vectorstore.UserVectorApp;


import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.google.common.io.Files;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import com.alibaba.cloud.ai.graph.store.StoreItem;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.store.StoreItem.*;
import com.tengjiao.douya.application.agent.*;


/**
 * 吃饭大师 - 多智能体版
 *
 * @author tengjiao
 * @since 2025-12-05 15:32
 */
@Slf4j
@Component
public class EatingMasterApp {

    protected String supervisorSystemPrompt = """
            你是一个智能的美食咨询监督者，负责协调不同领域的专家来为用户提供服务。

            ## 可用的子Agent及其职责

            ### VisionUnderstand
            - **功能**: 仅负责对用户上传的图片、视频进行深度解析。
            - **权限**: 图像识别、视频帧提取。

            ### EatingMaster (核心/兜底智能体)
            - **功能**: 处理美食文化、食谱生成、饮食建议。
            - **重要权限**: 它是唯一拥有 **`public_search`** (系统菜谱知识库) 和 **`memory_search`** (用户偏好记忆) 调用权的专家。
            - **规则**: 任何涉及“搜索标准做法”、“查找官方教程”、“食谱详情”或“记得我吗”的请求，**必须**交给它。

            ### DailyAssistant
            - **功能**: 处理非美食领域的通用查询（天气、财经等）。
            - **权限**: 实时互联网搜索。

            ## 响应格式
            只返回Agent名称（VisionUnderstand、EatingMaster、DailyAssistant）或 FINISH。不要包含解释。
            """;

    protected String supervisorInstruction = """
            请根据当前任务状态和用户需求（特别是 PromptRewriter 的优化指令），决定下一步操作：

            1. **防死循环机制 (绝对优先)**:
               - **检查上一条消息**:
                 - 如果是 `EatingMaster` 或 `DailyAssistant` 的发言（或者其结果已在上下文中） -> **立刻 FINISH**。
                 - 如果是 `VisionUnderstand` 的发言 -> **禁止**再次调用它，路由给 `EatingMaster` 进行总结。

            2. **工具指令路由 (EatingMaster专场)**:
               - **关键词触发**：如果输入中包含 "public_search"、"memory_search"、"搜索食谱"、"查找教程"、"食材清单"、"火候说明" 等明确要求使用某种检索能力的指令。
               - **操作**：**必须**路由给 **EatingMaster**。

            3. **视觉分析 (VisionUnderstand)**:
               - 用户上传了素材，且尚未进行高密度解析。如果是询问“你会不会看图”，则跳过此项交给 EatingMaster。

            4. **通用助手 (DailyAssistant)**:
               - 纯非美食领域需求（如金价、天气、新闻）。

            5. **兜底与终结逻辑**:
               - 如果任务已由专家产出最终答案 -> 返回 **FINISH**。
               - **默认降级 (Fallback)**：如果你无法准确判断该由谁处理，或者用户意图模糊，**务必返回 EatingMaster** 开启对话，不要返回 FINISH。

            current input:
            {input}
            """;


    private final ChatModel eatingMasterModel;
    private final ChatModel structTransformModel;
    private final ChatModel summaryChatModel;
    private final Store douyaDatabaseStore;
    private final UserVectorApp userVectorApp;
    private final ChatModel readUnderstandModel;
    private final ChatModel douBaoTransitDeepseek;

    private final Store memoryStore = new MemoryStore();

    public EatingMasterApp(ChatModel eatingMasterModel, ChatModel structTransformModel, ChatModel summaryChatModel, Store douyaDatabaseStore,
                           UserVectorApp userVectorApp, ChatModel readUnderstandModel, ChatModel douBaoTransitDeepseek) {
        this.eatingMasterModel = eatingMasterModel;
        this.structTransformModel = structTransformModel;
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
        PreferenceLearningHook preferenceLearningHook = new PreferenceLearningHook(summaryChatModel,
                douyaDatabaseStore);
        CombinedMemoryHook combinedMemoryHook = new CombinedMemoryHook(douyaDatabaseStore, summaryChatModel,
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
        PublicDocumentSearchTool publicDocTool = new PublicDocumentSearchTool(userVectorApp, douBaoTransitDeepseek);
        ToolCallback publicDocToolCallback = FunctionToolCallback.builder("public_search",
                publicDocTool::search)
                .description("检索系统公共知识库、官方手册、菜谱指南或操作说明。当用户询问专业知识、公共资源或需要查找带图片的官方资料时使用。")
                .inputType(PublicDocumentSearchTool.Request.class)
                .toolMetadata(DefaultToolMetadata.builder().returnDirect(true).build())
                .build();

        // 创建用户偏好注入拦截器 (由 userId 驱动)
        UserPreferInterceptors userPreferInterceptor = new UserPreferInterceptors(douyaDatabaseStore, userId);

        // 创建各类智能体
        EatingMasterAgent eatingMasterAgentObj = new EatingMasterAgent(eatingMasterModel,
                List.of(ragToolCallback, publicDocToolCallback),
                List.of(preferenceLearningHook, combinedMemoryHook),
                List.of(userPreferInterceptor));
        ReactAgent eatingMasterAgent = eatingMasterAgentObj.build();

        VisionUnderstandAgent visionAgentObj = new VisionUnderstandAgent(readUnderstandModel,
                Collections.emptyList(),
                List.of(preferenceLearningHook, combinedMemoryHook),
                List.of(userPreferInterceptor));
        ReactAgent visionAgent = visionAgentObj.build();

        DailyAssistantAgent dailyAgentObj = new DailyAssistantAgent(eatingMasterModel,
                Collections.emptyList(),
                List.of(preferenceLearningHook, combinedMemoryHook),
                Collections.emptyList());
        ReactAgent dailyAgent = dailyAgentObj.build();

        PromptRewriterAgent promptRewriterAgentObj = new PromptRewriterAgent(douBaoTransitDeepseek,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        ReactAgent promptRewriterAgent = promptRewriterAgentObj.build();

        ResponseFormatterAgent responseFormatterAgentObj = new ResponseFormatterAgent(structTransformModel,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        ReactAgent responseFormatterAgent = responseFormatterAgentObj.build();

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
                dailyAgent,
                promptRewriterAgent,
                responseFormatterAgent, // 新增
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
                // 核心：优先获取格式化后的结构化输出
                if (state.data().containsKey("ResponseFormatter")) {
                    Object output = state.data().get("ResponseFormatter");
                    if (output instanceof AssistantMessage am) {
                        // 这里不再调用 saveConversationPair，或者可以根据需要记录
                        saveConversationPair(userId, userMessage.getText(), am.getText(), "ResponseFormatter");
                        return am.getText();
                    }
                }

                // 备份：如果格式化器失败，尝试获取各子 Agent 的原始输出（为了鲁棒性）
                String[] agents = {"EatingMaster", "DailyAssistant", "VisionUnderstand"};
                for (String agent : agents) {
                    if (state.data().containsKey(agent)) {
                        Object output = state.data().get(agent);
                        if (output instanceof AssistantMessage am) {
                            saveConversationPair(userId, userMessage.getText(), am.getText(), agent);
                            return am.getText();
                        }
                    }
                }
                log.warn("[Multi-Agent] 未找到任何有效的输出，返回 State: {}", state.data());
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
