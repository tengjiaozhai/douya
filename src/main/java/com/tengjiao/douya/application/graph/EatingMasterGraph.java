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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Slf4j
public class EatingMasterGraph {

    private final ChatModel summaryChatModel;
    private final ReactAgent eatingMasterAgent;
    private final ReactAgent visionAgent;
    private final ReactAgent dailyAgent;
    private final ReactAgent promptRewriterAgent; // 新增
    private final String supervisorSystemPrompt;
    private final String supervisorInstruction;
    private final RunnableConfig config;

    public EatingMasterGraph(ChatModel summaryChatModel,
                             ReactAgent eatingMasterAgent,
                             ReactAgent visionAgent,
                             ReactAgent dailyAgent,
                             ReactAgent promptRewriterAgent, // 新参数
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
            strategies.put("PromptRewriter", new ReplaceStrategy()); // 新增
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
        NodeAction promptRewriterNode = state -> runAgent(promptRewriterAgent, state, "PromptRewriter"); // 新增

        // 3. 构建 StateGraph
        StateGraph graph = new StateGraph(keyStrategyFactory)
                .addNode("supervisor", node_async(supervisorNode))
                .addNode("EatingMaster", node_async(eatingMasterNode))
                .addNode("VisionUnderstand", node_async(visionNode))
                .addNode("DailyAssistant", node_async(dailyNode))
                .addNode("PromptRewriter", node_async(promptRewriterNode)) // 新增
                .addEdge(START, "PromptRewriter") // 修改：START -> PromptRewriter
                .addEdge("PromptRewriter", "supervisor") // 修改：PromptRewriter -> supervisor
                // 监督者路由逻辑：决定去哪个 Worker
                .addConditionalEdges(
                        "supervisor",
                        edge_async(state -> (String) state.value("next").orElse("FINISH")),
                        Map.of(
                                "EatingMaster", "EatingMaster",
                                "VisionUnderstand", "VisionUnderstand",
                                "DailyAssistant", "DailyAssistant", // 新增映射
                                "FINISH", END
                        )
                )
                // Worker 后的路由逻辑：如果已决策完成则直达 END，否则回流到 supervisor
                .addConditionalEdges(
                        "EatingMaster",
                        edge_async(state -> {
                            String next = (String) state.value("next").orElse("FINISH");
                            return "FINISH".equalsIgnoreCase(next) ? "FINISH" : "supervisor";
                        }),
                        Map.of("FINISH", END, "supervisor", "supervisor")
                )
                .addConditionalEdges(
                        "VisionUnderstand",
                        edge_async(state -> {
                            String next = (String) state.value("next").orElse("FINISH");
                            return "FINISH".equalsIgnoreCase(next) ? "FINISH" : "supervisor";
                        }),
                        Map.of("FINISH", END, "supervisor", "supervisor")
                )
                .addConditionalEdges( // 新增 DailyAssistant 的回流逻辑
                        "DailyAssistant",
                        edge_async(state -> {
                            String next = (String) state.value("next").orElse("FINISH");
                            return "FINISH".equalsIgnoreCase(next) ? "FINISH" : "supervisor";
                        }),
                        Map.of("FINISH", END, "supervisor", "supervisor")
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
            if (result instanceof OverAllState subState) {
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
}
