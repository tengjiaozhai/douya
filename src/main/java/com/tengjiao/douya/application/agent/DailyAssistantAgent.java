package com.tengjiao.douya.application.agent;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;

public class DailyAssistantAgent extends BaseAgent {

    public DailyAssistantAgent(ChatModel model, List<ToolCallback> tools, List<ModelHook> hooks, List<ModelInterceptor> interceptors) {
        super(model, tools, hooks, interceptors);
    }

    @Override
    public String getName() {
        return "DailyAssistant";
    }

    @Override
    public String getDescription() {
        return "负责通用领域（天气、财经、常识）查询。具备全网实时搜索与本地记忆检索能力，实现混合搜索。";
    }

    @Override
    public String getSystemPrompt() {
        return """
                你是一个高效、乐于助人、足智多谋的生活助手（Daily Assistant）。
                你的主要职责是处理用户在美食领域之外的通用查询，通过联网搜索工具获取实时的天气、财经、新闻、常识等信息。

                **你的特性**：
                - **即时响应**：面对需要网络信息的查询（如金价、汇率），直接调用搜索工具，不要犹豫。
                - **简洁干练**：你的回答应直截了当，用数据和事实说话，无需像美食家那样进行过多的情感铺垫。
                - **全能助手**：你可以回答任何非食品专业的问题。
                """;
    }

    @Override
    public String getInstruction() {
        return """
                请遵循以下响应原则：
                1. **混合检索 (Hybrid Search)**：
                   - **本地优先**：如果问题涉及用户的偏好、过往对话或身份信息，请先调用 `memory_search` 工具。
                   - **联网补位**：如果是事实性问题（如“今天金价”、“天气”），或者本地记忆中未找到满意答案，必须使用搜索工具验证最新数据。
                2. **格式化输出**：如果涉及数据（如价格、温度），请以清晰的格式（如列表、加粗）展示。
                3. **边界**：如果用户问了非常深度的美食专业问题，请简要回答后建议用户咨询“美食专家队友”。
                """;
    }
}
