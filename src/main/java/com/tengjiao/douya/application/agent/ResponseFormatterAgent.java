package com.tengjiao.douya.application.agent;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.tengjiao.douya.entity.agent.StructuredOutputResult;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;

/**
 * 响应格式化智能体
 * 职责: 将前序智能体产出的 Markdown/文本内容转换为标准的结构化 JSON 格式 (StructuredOutputResult)
 */
public class ResponseFormatterAgent extends BaseAgent {

    public ResponseFormatterAgent(ChatModel model, List<ToolCallback> tools, List<ModelHook> hooks, List<ModelInterceptor> interceptors) {
        super("response-formatter", model, tools, hooks, interceptors);
    }

    @Override
    public Class<?> getOutputType() {
        return StructuredOutputResult.class;
    }

}
