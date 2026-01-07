package com.tengjiao.douya.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class SupervisorNode implements NodeAction {

    private static final int MAX_ROUTING_COUNT = 5; // 最大路由次数限制

    private final ChatClient chatClient;
    private final List<String> members;
    private final String systemPrompt;
    private final String instruction;

    public SupervisorNode(ChatModel chatModel, List<String> members, String systemPrompt, String instruction) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.members = members;
        this.systemPrompt = systemPrompt;
        this.instruction = instruction;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // 1. 预检查：如果已经决策为结束，则直接返回
        String currentNext = (String) state.value("next").orElse("");
        if ("FINISH".equalsIgnoreCase(currentNext)) {
            return Map.of("next", "FINISH");
        }

        // 2. 检查路由次数限制
        Integer currentCount = (Integer) state.value("routing_count").orElse(0);
        if (currentCount >= MAX_ROUTING_COUNT) {
            log.warn("SupervisorNode: 达到最大路由次数 {}, 强制终止", MAX_ROUTING_COUNT);
            return Map.of("next", "FINISH");
        }

        // 2. 获取消息历史
        List<Object> messages = (List<Object>) state.value("messages").orElse(List.of());
        if (messages.isEmpty()) {
            // Should not happen if graph starts with input
            log.warn("SupervisorNode: No messages found in state.");
            return Map.of("next", "FINISH");
        }

        // 3. 提取最后一条消息内容作为 Input
        Object lastMsgObj = messages.get(messages.size() - 1);
        String lastMessageText = extractText(lastMsgObj);

        // 简单的上下文拼接，用于辅助 LLM 判断 (实际生产中可能需要更复杂的 History 序列化)
        // 这里主要为了防止死循环，我们需要知道上一条是谁说的
        String lastSpeaker = "User";
        if (lastMsgObj instanceof Message msg) {
             if ("assistant".equals(msg.getMessageType().getValue())) {
                 // 尝试推断是谁，这里假设 Graph 执行逻辑中会将 Agent 名字写入某种 Metadata 或者我们根据内容判断
                 // 在本架构中，Agent 的输出会被 append 到 messages。
                 // 我们可以简单的认为：如果是 AssistantMessage，那可能是之前的 Agent 输出的。
                 // 但为了严谨，我们主要依靠 content 和 context。
                 lastSpeaker = "Assistant";
             }
        }

        // 4. 构建 Prompt
        // 将 members 替换进 systemPrompt (如果需要)
        // 这里的 systemPrompt 和 instruction 是从 EatingMasterApp 传过来的，已经包含了必要的逻辑

        String inputContext = String.format("Current Request: %s\nLast Speaker detected: %s", lastMessageText, lastSpeaker);
        String finalInstruction = instruction.replace("{input}", inputContext);

        // 5. 调用 LLM
        String result = chatClient.prompt()
            .system(systemPrompt)
            .user(finalInstruction)
            .call()
            .content();

        // 6. 解析并规范化结果
        String next = normalizeRoute(result);
        log.info("Supervisor 路由决策 [第{}/{}次]: {} -> {}",
            currentCount + 1, MAX_ROUTING_COUNT, lastMessageText, next);

        // 7. 更新状态：路由次数和历史
        Map<String, Object> output = new HashMap<>();
        output.put("next", next);
        output.put("routing_count", currentCount + 1);
        output.put("routing_history", List.of(Map.of(
            "step", currentCount + 1,
            "decision", next,
            "input", lastMessageText,
            "timestamp", System.currentTimeMillis()
        )));

        return output;
    }

    private String normalizeRoute(String result) {
        if (result == null || result.trim().isEmpty()) {
            return "FINISH";
        }
        String normalized = result.trim();

        // 移除可能的标点
        normalized = normalized.replaceAll("[。.]", "");

        // 检查是否是大写 FINISH
        if (normalized.equalsIgnoreCase("FINISH")) {
            return "FINISH";
        }

        // 检查是否匹配任何成员
        for (String member : members) {
            // 忽略大小写比较，但返回准确的 beanName / key
            if (normalized.equalsIgnoreCase(member)) {
                return member;
            }
        }

        // Fallback: 如果 LLM 说了些别的，尝试找关键字
        for (String member : members) {
            if (normalized.contains(member)) {
                return member;
            }
        }

        return "FINISH";
    }

    private String extractText(Object msgObj) {
        if (msgObj instanceof Message msg) {
            return msg.getText();
        }
        if (msgObj instanceof String str) {
            return str;
        }
        // 处理 Map (JSON 反序列化场景)
        if (msgObj instanceof Map map) {
            Object content = map.get("content");
            return content != null ? content.toString() : "";
        }
        return msgObj.toString();
    }
}
