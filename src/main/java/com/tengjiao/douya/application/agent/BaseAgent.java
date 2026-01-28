package com.tengjiao.douya.application.agent;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;

public abstract class BaseAgent {
    protected final ChatModel model;
    protected final List<ToolCallback> tools;
    protected final List<ModelHook> hooks;
    protected final List<ModelInterceptor> interceptors;

    public BaseAgent(ChatModel model, List<ToolCallback> tools, List<ModelHook> hooks, List<ModelInterceptor> interceptors) {
        this.model = model;
        this.tools = tools;
        this.hooks = hooks;
        this.interceptors = interceptors;
    }

    public abstract String getName();
    public abstract String getDescription();
    public abstract String getSystemPrompt();
    public abstract String getInstruction();

    public ReactAgent build() {
        Builder builder = ReactAgent.builder()
            .name(getName())
            .description(getDescription())
            .model(model)
            .systemPrompt(getSystemPrompt())
            .instruction(getInstruction())
            .outputKey(getName());

        if (tools != null && !tools.isEmpty()) {
            builder.tools(tools.toArray(new ToolCallback[0]));
        }
        if (hooks != null && !hooks.isEmpty()) {
            builder.hooks(hooks.toArray(new ModelHook[0]));
        }
        if (interceptors != null && !interceptors.isEmpty()) {
            builder.interceptors(interceptors.toArray(new ModelInterceptor[0]));
        }

        return builder.build();
    }
}
