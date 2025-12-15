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

        // 分离消息，准备归档
        List<Message> messagesToKeep = new ArrayList<>();
        List<Message> messagesToArchive = new ArrayList<>();

        List<Message> contentMessages = new ArrayList<>();
        List<Message> systemMessages = new ArrayList<>();

        for (Message msg : messages) {
            if (msg instanceof SystemMessage) {
                systemMessages.add(msg);
            } else {
                contentMessages.add(msg);
            }
        }

        // 计算需要保留的内容消息数量 (阈值 - 系统消息数量)
        int capacity = threshold - systemMessages.size();
        if (capacity < 1) {
            capacity = 1; // 至少保留一条内容消息
        }

        if (contentMessages.size() > capacity) {
            int cutOff = contentMessages.size() - capacity;

            // 归档较早的消息
            messagesToArchive.addAll(contentMessages.subList(0, cutOff));

            // 重组当前上下文：系统消息 + 最近的消息
            messagesToKeep.addAll(systemMessages);
            messagesToKeep.addAll(contentMessages.subList(cutOff, contentMessages.size()));

            // 执行归档操作
            archiveMessages(userId, messagesToArchive);

            // 返回更新后的 State，Agent 将使用缩减后的消息列表进行思考
            log.info("User {} memory truncated. Archived: {}, Kept: {}", userId, messagesToArchive.size(), messagesToKeep.size());
            return CompletableFuture.completedFuture(Map.of("messages", messagesToKeep));
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
