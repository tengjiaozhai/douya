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

    /**
     * 定义智能体的输出类型。默认为 null (输出为字符串)。
     * 如果子类重写此方法并返回一个 Class，则输出将自动转换为该类型的结构化数据。
     */
    public Class<?> getOutputType() {
        return null;
    }

    public ReactAgent build() {
        Builder builder = ReactAgent.builder()
            .name(getName())
            .description(getDescription())
            .model(model)
            .systemPrompt(getSystemPrompt())
            .instruction(getInstruction())
            .outputKey(getName());

        Class<?> outputType = getOutputType();
        if (outputType != null) {
            builder.outputType(outputType);
        }

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
