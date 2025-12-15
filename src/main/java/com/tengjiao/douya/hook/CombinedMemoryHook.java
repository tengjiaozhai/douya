package com.tengjiao.douya.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 记忆结合 Hook
 * <p>
 * 实现短期记忆（内存）与长期记忆（持久化存储）的自动流转：
 * 当上下文中的消息数量超过阈值（如10条）时，将较早的消息归档到长期存储中，
 * 仅保留系统提示词和最近的消息，以控制上下文窗口大小并保存历史。
 *
 * @author tengjiao
 * @since 2025-12-15 14:49
 */
@Slf4j
public class CombinedMemoryHook extends ModelHook {

    private final Store longTermStore;
    private final int threshold;

    public CombinedMemoryHook(Store longTermStore, int threshold) {
        this.longTermStore = longTermStore;
        this.threshold = threshold;
    }

    @Override
    public String getName() {
        return "combined_memory";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_MODEL};
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Optional<List<Message>> messagesOpt = state.value("messages");

        // 如果没有消息或消息数量未超过阈值，不做处理
        if (messagesOpt.isEmpty() || messagesOpt.get().size() <= threshold) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> messages = messagesOpt.get();
        Optional<Object> userIdOpt = config.metadata("user_id");
        if (userIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String userId = (String) userIdOpt.get();
        // 归档最新消息
        if (messages.size() > threshold) {
            int cutOff = messages.size() - threshold;
            List<Message> messagesToArchive = messages.subList(0, cutOff);
            archiveMessages(userId, messagesToArchive);
            log.info("User {} memory truncated. Archived: {}", userId, messagesToArchive.size());
            return CompletableFuture.completedFuture(Map.of("messages", messages.subList(cutOff, messages.size())));
        }

        return CompletableFuture.completedFuture(Map.of());
    }

    private void archiveMessages(String userId, List<Message> messages) {
        // 定义长期记忆的存储 Key
        List<String> namespace = List.of("memory", "archive");

        try {
            // 加载现有的归档（如果存在）
            Optional<StoreItem> existingOpt = longTermStore.getItem(namespace, userId);
            List<Message> archivedHistory = new ArrayList<>();

            if (existingOpt.isPresent()) {
                Map<String, Object> data = existingOpt.get().getValue();
                if (data.containsKey("messages")) {
                    Object msgsObj = data.get("messages");
                    if (msgsObj instanceof List) {
                        // 注意：这里反序列化可能需要额外处理，暂时假设 Store 能够正确处理 Message 类型
                        archivedHistory.addAll((List<Message>) msgsObj);
                    }
                }
            }

            // 追加新归档的消息
            archivedHistory.addAll(messages);

            // 保存回长期存储
            Map<String, Object> value = new HashMap<>();
            value.put("messages", archivedHistory);
            value.put("updated_at", System.currentTimeMillis());

            StoreItem item = StoreItem.of(namespace, userId, value);
            longTermStore.putItem(item);

        } catch (Exception e) {
            log.error("Failed to archive messages for user " + userId, e);
            // 即使归档失败，也建议不要阻断主流程，但可能会导致数据丢失
        }
    }
}
