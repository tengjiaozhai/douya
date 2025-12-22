package com.tengjiao.douya.hook;

import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.tengjiao.douya.app.UserVectorApp;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 增强 Hook
 * 在大模型调用之前，根据用户问题从向量库中检索相关的长期记忆（摘要），并注入到上下文窗口中。
 *
 * @author tengjiao
 * @since 2025-12-22
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_MODEL})
public class RAGMessagesHook extends MessagesModelHook {

    private final UserVectorApp userVectorApp;
    private static final int TOP_K = 3;

    public RAGMessagesHook(UserVectorApp userVectorApp) {
        this.userVectorApp = userVectorApp;
    }

    @Override
    public String getName() {
        return "rag_messages_hook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        // 1. 获取 userId
        String userId = (String) config.metadata("user_id").orElse("default_user");

        // 2. 提取用户最新的问题
        String userQuestion = extractUserQuestion(previousMessages);
        if (userQuestion == null || userQuestion.isEmpty()) {
            return new AgentCommand(previousMessages);
        }

        // 3. 检索相关知识（长期记忆摘要）
        List<Document> relevantDocs = List.of();
        try {
            relevantDocs = userVectorApp.searchSimilar(userQuestion, userId, TOP_K, 0.5);
        } catch (Exception e) {
            log.error("RAG 检索失败", e);
        }

        if (relevantDocs.isEmpty()) {
            log.info("[RAG] 未找到与 '{}' 相关的长期记忆", userQuestion);
            return new AgentCommand(previousMessages);
        }

        // 4. 构建增强的背景知识
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        log.info("[RAG] 检索到 {} 条相关记忆，准备注入上下文", relevantDocs.size());

        // 5. 组装增强后的消息列表
        List<Message> enhancedMessages = new ArrayList<>();
        
        // 注入 RAG 背景提示词
        String ragPrompt = String.format("""
            你是一个经验丰富的美食家。以下是与当前问题相关的历史对话摘要或背景知识，请结合这些信息来提供更精准的回答：
            
            [背景知识库]:
            %s
            
            如果以上信息与当前问题无关，请忽略并基于你的专业知识回答。
            """, context);
            
        enhancedMessages.add(new SystemMessage(ragPrompt));
        // 保留原有的活跃上下文（短期记忆）
        enhancedMessages.addAll(previousMessages);

        // 使用 REPLACE 策略重写当前模型的输入消息
        return new AgentCommand(enhancedMessages, UpdatePolicy.REPLACE);
    }

    private String extractUserQuestion(List<Message> messages) {
        // 从后往前找最后一个用户消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage) {
                return msg.getText();
            }
        }
        return null;
    }
}
