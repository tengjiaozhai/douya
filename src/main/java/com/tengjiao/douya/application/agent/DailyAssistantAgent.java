package com.tengjiao.douya.application.agent;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;

public class DailyAssistantAgent extends BaseAgent {

    public DailyAssistantAgent(ChatModel model, List<ToolCallback> tools, List<ModelHook> hooks, List<ModelInterceptor> interceptors) {
        super("daily-assistant", model, tools, hooks, interceptors);
    }

}
