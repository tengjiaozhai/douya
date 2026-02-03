package com.tengjiao.douya.application.agent;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;

public abstract class BaseAgent {
    protected final ChatModel model;
    protected final List<ToolCallback> tools;
    protected final List<ModelHook> hooks;
    protected final List<ModelInterceptor> interceptors;

    protected final String skillName;
    protected final SkillsAgentHook agentHook;

    public BaseAgent(String skillName, ChatModel model, List<ToolCallback> tools, List<ModelHook> hooks, List<ModelInterceptor> interceptors, SkillsAgentHook agentHook) {
        this.skillName = skillName;
        this.model = model;
        this.tools = tools;
        this.hooks = hooks;
        this.interceptors = interceptors;

        // Load skill data
        SkillRegistry registry = ClasspathSkillRegistry.builder()
            .classpathPath("skills/"+skillName)
            .build();

        this.agentHook = SkillsAgentHook.builder()
            .skillRegistry(registry)
            .build();
    }

    /**
     * 定义智能体的输出类型。默认为 null (输出为字符串)。
     * 如果子类重写此方法并返回一个 Class，则输出将自动转换为该类型的结构化数据。
     */
    public Class<?> getOutputType() {
        return null;
    }

    public ReactAgent build() {
        Builder builder = ReactAgent.builder()
            .name(skillName)
            .hooks(List.of(agentHook))
            .model(model)
            .outputKey(skillName);

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
