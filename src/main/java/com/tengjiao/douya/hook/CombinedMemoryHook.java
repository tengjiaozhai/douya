package com.tengjiao.douya.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.tengjiao.douya.app.UserVectorApp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

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
    private final ChatModel summaryModel;
    private final UserVectorApp userVectorApp;
    private final int threshold;
    private final int archiveBatchSize; // 归档批大小，设为 10 表示每满 10 条才总结一次，节省资源

    public CombinedMemoryHook(Store longTermStore, ChatModel summaryModel, UserVectorApp userVectorApp, int threshold, int archiveBatchSize) {
        this.longTermStore = longTermStore;
        this.summaryModel = summaryModel;
        this.userVectorApp = userVectorApp;
        this.threshold = threshold;
        this.archiveBatchSize = archiveBatchSize;
    }

    @Override
    public String getName() {
        return "combined_memory";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.AFTER_MODEL};
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        Optional<List<Message>> messagesOpt = state.value("messages");

        // 如果没有消息，不做处理
        if (messagesOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> messages = messagesOpt.get();
        // 只有当消息总数达到 阈值 + 批处理大小 时，才触发归档和总结
        // 例如：threshold=10, batchSize=10，则当消息达到 20 条时，一次性总结掉前 10 条，剩下 10 条作为活跃上下文
        if (messages.size() < (threshold + archiveBatchSize)) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Optional<Object> userIdOpt = config.metadata("user_id");
        if (userIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String userId = (String) userIdOpt.get();

        // 归档并总结这一批次的消息
        int cutOff = archiveBatchSize; 
        List<Message> messagesToArchive = new ArrayList<>(messages.subList(0, cutOff));

        // 1. 持久化到长期存储 (数据库)
        archiveMessages(userId, messagesToArchive);

        // 2. 异步生成摘要并存入向量库 (知识库)
        Thread.startVirtualThread(() -> {
            try {
                String summary = generateSummary(messagesToArchive);
                if (summary != null && !summary.trim().isEmpty()) {
                    Document doc = new Document(summary);
                    doc.getMetadata().put("type", "conversation_summary");
                    doc.getMetadata().put("archived_count", messagesToArchive.size());
                    userVectorApp.addDocuments(List.of(doc), userId);
                    log.info("Successfully vectorized summary (batch of {}) for user {}: {}", archiveBatchSize, userId, summary);
                }
            } catch (Exception e) {
                log.error("Failed to summarize/vectorize messages for user " + userId, e);
            }
        });

        log.info("User {} memory batch archived. Size: {}, Remaining: {}", userId, cutOff, messages.size() - cutOff);
        // 返回截断后的列表
        return CompletableFuture.completedFuture(Map.of("messages", new ArrayList<>(messages.subList(cutOff, messages.size()))));
    }

    private String generateSummary(List<Message> messages) {
        StringBuilder conversation = new StringBuilder();
        for (Message msg : messages) {
            String role = msg.getMessageType().getValue();
            conversation.append(role).append(": ").append(msg.getText()).append("\n");
        }

        String prompt = """
            请为以下对话片段生成一个简洁的摘要（100字以内）。
            该摘要将作为用户的长期记忆知识库，用于后续问题的召回。
            请确保摘要提取了关键的地理位置、人物、意图、偏好或重要的决策信息。

            对话内容：
            %s

            摘要：
            """.formatted(conversation.toString());

        try {
            ChatResponse response = summaryModel.call(new Prompt(new UserMessage(prompt)));
            return response.getResult().getOutput().getText().trim();
        } catch (Exception e) {
            log.error("AI 总结会话失败", e);
            return null;
        }
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
