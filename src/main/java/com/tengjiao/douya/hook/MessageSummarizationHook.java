package com.tengjiao.douya.hook;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.RemoveByHash;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class MessageSummarizationHook extends ModelHook {
    private final ChatModel summaryModel;
    private final int maxTokensBeforeSummary;
    private final int messagesToKeep;

    public MessageSummarizationHook(ChatModel summaryModel, int maxTokensBeforeSummary, int messagesToKeep) {
        this.summaryModel = summaryModel;
        this.maxTokensBeforeSummary = maxTokensBeforeSummary;
        this.messagesToKeep = messagesToKeep;
    }

    @Override
    public String getName() {
        return "message_summarization";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_MODEL};
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Optional<Object> messagesOpt = state.value("messages");
        if (!messagesOpt.isPresent()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<Message> messages = (List<Message>) messagesOpt.get(); // 估算 token 数量（简化版）
        int estimatedTokens = messages.stream().mapToInt(m -> m.getText().length() / 4).sum();
        if (estimatedTokens < maxTokensBeforeSummary) {
            return CompletableFuture.completedFuture(Map.of());
        }
        // 需要总结
        int messagesToSummarize = messages.size() - messagesToKeep;
        if (messagesToSummarize <= 0) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<Message> oldMessages = messages.subList(0, messagesToSummarize);
        // 生成摘要
        String summary = generateSummary(oldMessages);
        // 创建摘要消息
        SystemMessage summaryMessage = new SystemMessage("## 之前对话摘要: " + summary);
        // 只需要把摘要消息和需要删除的消息保留在状态中，其余未包含的消息将会自动保留
        List<Object> newMessages = new ArrayList<>();
        newMessages.add(summaryMessage);
        // IMPORTANT! Convert summarized messages to RemoveByHash objects so we can remove them from state
        for (Message msg : oldMessages) {
            newMessages.add(RemoveByHash.of(msg));
        }
        return CompletableFuture.completedFuture(Map.of("messages", newMessages));
    }

    private String generateSummary(List<Message> messages) {
        StringBuilder conversation = new StringBuilder();
        for (Message msg : messages) {
            conversation.append(msg.getMessageType()).append(": ").append(msg.getText()).append(" ");
        }
        String summaryPrompt = "请简要总结以下对话: " + conversation;
        ChatResponse response = summaryModel.call(new Prompt(new UserMessage(summaryPrompt)));
        return response.getResult().getOutput().getText();
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());
    }
}
