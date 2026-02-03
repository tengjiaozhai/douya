package com.tengjiao.douya.application.agent;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.tengjiao.douya.entity.feishu.content.FeishuPostContent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;

public class EatingMasterAgent extends BaseAgent {

    public EatingMasterAgent(ChatModel model, List<ToolCallback> tools, List<ModelHook> hooks, List<ModelInterceptor> interceptors) {
        super("eating-master", model, tools, hooks, interceptors);
    }

}
