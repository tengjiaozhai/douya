package com.tengjiao.douya.application.graph;



import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Slf4j
public class EatingMasterGraph {
    private static final Pattern OSS_URL_PATTERN =
            Pattern.compile("(?i)ossUrl\\s*=\\s*(https?://[^\\s\"'\\\\)\\]]+)");

    private final ChatModel summaryChatModel;
    private final ReactAgent eatingMasterAgent;
    private final ReactAgent visionAgent;
    private final ReactAgent dailyAgent;
    private final ReactAgent promptRewriterAgent;
    private final String supervisorSystemPrompt;
    private final String supervisorInstruction;
    private final RunnableConfig config;

    public EatingMasterGraph(ChatModel summaryChatModel,
                             ReactAgent eatingMasterAgent,
                             ReactAgent visionAgent,
                             ReactAgent dailyAgent,
                             ReactAgent promptRewriterAgent,
                             String supervisorSystemPrompt,
                             String supervisorInstruction,
                             RunnableConfig config) {
        this.summaryChatModel = summaryChatModel;
        this.eatingMasterAgent = eatingMasterAgent;
        this.visionAgent = visionAgent;
        this.dailyAgent = dailyAgent;
        this.promptRewriterAgent = promptRewriterAgent;
        this.supervisorSystemPrompt = supervisorSystemPrompt;
        this.supervisorInstruction = supervisorInstruction;
        this.config = config;
    }

    public CompiledGraph createGraph() throws Exception {
        // 1. 定义状态策略
        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy()); // 消息历史追加
            strategies.put("next", new ReplaceStrategy());    // 路由状态覆盖
            strategies.put("EatingMaster", new ReplaceStrategy());
            strategies.put("VisionUnderstand", new ReplaceStrategy());
            strategies.put("DailyAssistant", new ReplaceStrategy());
            strategies.put("PromptRewriter", new ReplaceStrategy());
            strategies.put("routing_count", new ReplaceStrategy()); // 路由次数计数
            strategies.put("routing_history", new AppendStrategy()); // 路由历史追踪
            return strategies;
        };

        // 2. 创建节点
        // 2.1 Supervisor Node
        SupervisorNode supervisorNode = new SupervisorNode(
                summaryChatModel,
                List.of("EatingMaster", "VisionUnderstand", "DailyAssistant"),
                supervisorSystemPrompt,
                supervisorInstruction
        );

        // 2.2 Worker Nodes (Wrapped)
        NodeAction eatingMasterNode = state -> runAgent(eatingMasterAgent, state, "EatingMaster");
        NodeAction visionNode = state -> runAgent(visionAgent, state, "VisionUnderstand");
        NodeAction dailyNode = state -> runAgent(dailyAgent, state, "DailyAssistant");
        NodeAction promptRewriterNode = state -> runAgent(promptRewriterAgent, state, "PromptRewriter");

        // 3. 构建 StateGraph
        StateGraph graph = new StateGraph(keyStrategyFactory)
                .addNode("supervisor", node_async(supervisorNode))
                .addNode("EatingMaster", node_async(eatingMasterNode))
                .addNode("VisionUnderstand", node_async(visionNode))
                .addNode("DailyAssistant", node_async(dailyNode))
                .addNode("PromptRewriter", node_async(promptRewriterNode))
                .addEdge(START, "PromptRewriter")
 // 修改：START -> PromptRewriter
                .addEdge("PromptRewriter", "supervisor") // 修改：PromptRewriter -> supervisor
                // 监督者路由逻辑：决定去哪个 Worker
                .addConditionalEdges(
                        "supervisor",
                        edge_async(state -> (String) state.value("next").orElse("FINISH")),
                        Map.of(
                                "EatingMaster", "EatingMaster",
                                "VisionUnderstand", "VisionUnderstand",
                                "DailyAssistant", "DailyAssistant",
                                "FINISH", END // 改为指向格式化器
                        )
                )
                // Worker 后的路由逻辑：如果已决策完成则直达 END，否则回流到 supervisor
                .addConditionalEdges(
                        "EatingMaster",
                        edge_async(state -> {
                            String next = (String) state.value("next").orElse("FINISH");
                            return "FINISH".equalsIgnoreCase(next) ? END : "supervisor";
                        }),
                        Map.of(END, END, "supervisor", "supervisor")
                )
                .addConditionalEdges(
                        "VisionUnderstand",
                        edge_async(state -> {
                            String next = (String) state.value("next").orElse("FINISH");
                            return "FINISH".equalsIgnoreCase(next) ? END : "supervisor";
                        }),
                        Map.of(END, END, "supervisor", "supervisor")
                )
                .addConditionalEdges( // DailyAssistant fallback routing
                        "DailyAssistant",
                        edge_async(state -> {
                            String next = (String) state.value("next").orElse("FINISH");
                            return "FINISH".equalsIgnoreCase(next) ? END : "supervisor";
                        }),
                        Map.of(END, END, "supervisor", "supervisor")
                );

        return graph.compile();
    }

    /**
     * 运行子 Agent 的适配器逻辑
     */
    private Map<String, Object> runAgent(ReactAgent agent, OverAllState state, String agentName) {
        try {
            // 获取最后一条消息作为输入
            List<Object> messages = (List<Object>) state.value("messages").orElse(List.of());
            String lastText = "";
            if (!messages.isEmpty()) {
                Object last = messages.get(messages.size() - 1);
                if (last instanceof Message m) {
                    lastText = m.getText();
                } else {
                    lastText = last.toString();
                }
            }

            log.info("Invoking Agent [{}] with input: {}", agentName, lastText);

            // 使用传入的 config 调用 agent
            Object result = agent.invoke(new UserMessage(lastText), this.config).orElse(null);

            String responseText = "Agent failed to respond.";
            List<String> toolImageUrls = List.of();
            if (result instanceof OverAllState subState) {
                toolImageUrls = extractImageUrlsFromState(subState);
                if (subState.data().containsKey(agentName)) { // outputKey
                    Object out = subState.data().get(agentName);
                    if (out instanceof AssistantMessage am) {
                        responseText = am.getText();
                    } else {
                        responseText = out.toString();
                    }
                } else {
                    List<Object> subMsgs = (List<Object>) subState.value("messages").orElse(List.of());
                    if (!subMsgs.isEmpty()) {
                        Object last = subMsgs.get(subMsgs.size() - 1);
                        if (last instanceof AssistantMessage am) {
                            responseText = am.getText();
                        }
                    }
                }
            } else if (result != null) {
                // 如果结果不是 State，可能是 Map 或其他
                // 暂时 toString
                responseText = result.toString();
            }

            if ("EatingMaster".equals(agentName)) {
                responseText = appendMissingImageAssets(responseText, toolImageUrls);
            }

            log.info("Agent [{}] response: {}", agentName, responseText);

            AssistantMessage assistantMessage = new AssistantMessage(responseText);

            Map<String, Object> output = new HashMap<>();
            output.put("messages", List.of(assistantMessage)); // 追加到历史
            output.put(agentName, assistantMessage); // 更新特定 Key

            return output;

        } catch (Exception e) {
            log.error("Agent execution failed", e);
            return Map.of("messages", List.of(new AssistantMessage("Agent Error: " + e.getMessage())));
        }
    }

    private List<String> extractImageUrlsFromState(OverAllState subState) {
        Set<String> urls = new LinkedHashSet<>();

        Object messagesObj = subState.value("messages").orElse(List.of());
        if (messagesObj instanceof List<?> messages) {
            for (Object msgObj : messages) {
                extractImageUrlsFromObject(msgObj, urls);
            }
        }

        // Fallback: some tool payloads may only exist in serialized state data.
        extractImageUrlsFromText(String.valueOf(subState.data()), urls);
        return new ArrayList<>(urls);
    }

    private void extractImageUrlsFromObject(Object obj, Set<String> urls) {
        if (obj == null) {
            return;
        }

        if (obj instanceof Message message) {
            extractImageUrlsFromText(message.getText(), urls);
        }

        Object responsesObj = tryInvokeNoArgs(obj, "getResponses");
        if (responsesObj == null) {
            responsesObj = tryInvokeNoArgs(obj, "responses");
        }
        if (responsesObj instanceof Iterable<?> responses) {
            for (Object response : responses) {
                Object responseData = tryInvokeNoArgs(response, "getResponseData");
                if (responseData == null) {
                    responseData = tryInvokeNoArgs(response, "responseData");
                }
                if (responseData != null) {
                    extractImageUrlsFromText(String.valueOf(responseData), urls);
                }
                extractImageUrlsFromText(String.valueOf(response), urls);
            }
        }

        extractImageUrlsFromText(String.valueOf(obj), urls);
    }

    private Object tryInvokeNoArgs(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void extractImageUrlsFromText(String text, Set<String> urls) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = OSS_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = matcher.group(1);
            if (url != null && !url.isBlank()) {
                urls.add(url.trim());
            }
        }
    }

    private String appendMissingImageAssets(String responseText, List<String> toolImageUrls) {
        if (toolImageUrls == null || toolImageUrls.isEmpty()) {
            return responseText;
        }

        Set<String> existingUrls = new LinkedHashSet<>();
        extractImageUrlsFromText(responseText, existingUrls);

        List<String> missing = toolImageUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .filter(url -> !existingUrls.contains(url))
                .toList();

        if (missing.isEmpty()) {
            return responseText;
        }

        StringBuilder sb = new StringBuilder(responseText == null ? "" : responseText.strip());
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("相关图片资产:\n");
        for (String url : missing) {
            sb.append("[图片资产]: ossUrl=").append(url).append("\n");
        }
        return sb.toString().strip();
    }
}
