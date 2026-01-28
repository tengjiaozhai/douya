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
        super(model, tools, hooks, interceptors);
    }

    @Override
    public Class<?> getOutputType() {
        return StructuredOutputResult.class;
    }

    @Override
    public String getName() {
        return "ResponseFormatter";
    }

    @Override
    public String getDescription() {
        return "负责将非结构化的文本内容转换为标准的飞书富文本 JSON 结构。";
    }

    @Override
    public String getSystemPrompt() {
        return """
                你是一个高效率的响应格式化专家。
                你的唯一目标是阅读对话历史中最后一条来自其他 Assistant 的 Markdown 消息，并将其完整、准确地转换为指定的 `StructuredOutputResult` JSON 结构。
                """;
    }

    @Override
    public String getInstruction() {
        return """
                请严格遵循以下规则进行格式化转换：
                
                1. **内容提取**: 提取上一条消息的核心正文。
                2. **结构化组装 (FeishuPostContent)**:
                   - **title**: 根据内容生成一个简短有力的标题。
                   - **content**: 按照段落拆分。
                     - 文字部分使用 `tag: "text"`。
                     - 发现超链接使用 `tag: "a"`, `text: "名称"`, `href: "url"`。
                     - **图片保留 (至关重要)**: 发现在 Markdown 中的图片 `![描述](url)` 时，必须转换为 `tag: "img"`, `url: "[url]"`。
                3. **元数据填充**:
                   - **thoughts**: 简要说明你提取和转换的过程（如：提取了食谱步骤并保留了 2 张图片链接）。
                   - **action**: 固定为 "SEND_FEISHU"。
                   - **tags**: 提取 2-4 个反映内容的标签。
                
                注意：不要添加任何解释性文字，只输出符合 Schema 的 JSON。
                """;
    }
}
