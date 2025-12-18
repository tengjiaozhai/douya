package com.tengjiao.douya.hook;

import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * 偏好学习 Hook
 * 使用 DeepSeek 模型分析用户消息,提取用户偏好并存储到长期记忆中
 *
 * @author tengjiao
 * @since 2025-12-11 09:34
 */
@Slf4j
public class PreferenceLearningHook extends ModelHook {

    private final ChatModel preferenceLearningModel;
    private final Store douyaDatabaseStore;

    /**
     * 构造函数
     *
     * @param preferenceLearningModel DeepSeek 模型,用于分析用户偏好
     * @param douyaDatabaseStore      内存存储,用于持久化用户偏好
     */
    public PreferenceLearningHook(ChatModel preferenceLearningModel, Store douyaDatabaseStore) {
        this.preferenceLearningModel = preferenceLearningModel;
        this.douyaDatabaseStore = douyaDatabaseStore;
    }

    @Override
    public String getName() {
        return "preference_learning";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.AFTER_MODEL};
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        // 获取 userId
        String userId = (String) config.metadata("user_id").orElse(null);
        if (userId == null) {
            log.warn("未找到 user_id,跳过偏好学习");
            return CompletableFuture.completedFuture(Map.of());
        }
        List<Message> messages = (List<Message>) state.value("messages").orElse(new ArrayList<>());
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // 获取最后一条用户消息
        Message lastMessage = IntStream.iterate(messages.size() - 1, i -> i >= 0, i -> i - 1).mapToObj(messages::get).filter(message -> message instanceof UserMessage).findFirst().orElse(null);
        if (lastMessage == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Thread.startVirtualThread(() -> {
            // 这个逻辑在另一个线程异步执行
            // 提取用户输入消息
            String userInput = lastMessage.getText();

            // 使用 DeepSeek 模型提取偏好
            String extractedPreference = extractPreference(userInput);

            if (extractedPreference != null && !extractedPreference.trim().isEmpty()) {
                // 加载现有偏好
                Set<String> preferences = loadPreferences(userId);

                // 添加新偏好
                preferences.add(extractedPreference);

                // 保存偏好
                savePreferences(userId, preferences);

                log.info("学习到用户偏好 [{}]: {}", userId, extractedPreference);
            }

        });

        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * 使用 DeepSeek 模型提取用户偏好
     *
     * @param userInput 用户输入
     * @return 提取的偏好,如果没有偏好则返回 null
     */
    private String extractPreference(String userInput) {
        String prompt = """
            请分析以下用户消息,提取其中表达的饮食偏好、口味偏好、饮食习惯等信息。

            规则:
            1. 只提取明确的偏好信息,如"喜欢"、"偏好"、"不喜欢"、"讨厌"、"习惯"、"不太能"等
            2. 如果消息中没有明确的偏好信息,直接返回"无"
            3. 提取的偏好要简洁明了,一句话概括
            4. 只返回偏好内容,不要有任何解释或额外文字

            用户消息: %s

            提取的偏好:
            """.formatted(userInput);

        try {
            ChatResponse response = preferenceLearningModel.call(new Prompt(new UserMessage(prompt)));
            String result = response.getResult().getOutput().getText().trim();

            // 如果返回"无"或空字符串,则返回 null
            if (result.equals("无") || result.isEmpty()) {
                return null;
            }

            return result;
        } catch (Exception e) {
            log.error("提取偏好失败", e);
            return null;
        }
    }

    /**
     * 加载用户偏好
     *
     * @param userId 用户 ID
     * @return 偏好列表
     */
    private Set<String> loadPreferences(String userId) {
        Optional<StoreItem> prefsOpt = douyaDatabaseStore.getItem(List.of("user_data"), userId + "_preferences");
        if (prefsOpt.isPresent()) {
            Map<String, Object> prefsData = prefsOpt.get().getValue();
            Object items = prefsData.get("items");
            if (items instanceof Collection) {
                return new LinkedHashSet<>((Collection<String>) items);
            }
        }
        return new LinkedHashSet<>();
    }

    /**
     * 保存用户偏好
     *
     * @param userId      用户 ID
     * @param preferences 偏好列表
     */
    private void savePreferences(String userId, Set<String> preferences) {
        Map<String, Object> prefsData = new HashMap<>();
        prefsData.put("items", new ArrayList<>(preferences)); // 转换为 List 存储以保证兼容性

        StoreItem item = StoreItem.of(List.of("user_data"), userId + "_preferences", prefsData);
        douyaDatabaseStore.putItem(item);
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());
    }
}
