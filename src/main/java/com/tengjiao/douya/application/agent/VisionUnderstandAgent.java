package com.tengjiao.douya.application.agent;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;

public class VisionUnderstandAgent extends BaseAgent {

    public VisionUnderstandAgent(ChatModel model, List<ToolCallback> tools, List<ModelHook> hooks, List<ModelInterceptor> interceptors) {
        super(model, tools, hooks, interceptors);
    }

    @Override
    public String getName() {
        return "VisionUnderstand";
    }

    @Override
    public String getDescription() {
        return "负责深度解析视觉素材。注意：仅当检测到明确的视觉素材附件时才调用。";
    }

    @Override
    public String getSystemPrompt() {
        return """
                你是一个精通多模态识别的资深视觉分析专家。你拥有极强的观察力与客观描述能力。
                你的任务是将复杂的视觉信号转化为详尽、客观且结构化的文本描述。
                """;
    }

    @Override
    public String getInstruction() {
        return """
                请以【最高信息密度】分析素材，严格遵守以下规范：
                1. **特殊情况**：
                   - 如果用户询问能力（如“你会看图吗”），请回答：“**我具备视觉分析能力。请发送图片或视频，我将为您深入解析食材与环境。**”
                   - 如果未提供素材且非能力询问，请回答：“**请先上传视觉素材（图片/视频），以便我为您进行多模态分析。**”
                2. **严禁开场白**：直接输出数据。
                3. **纯粹事实**：确保文字精炼，禁用第一人称。
                4. **结构化格式**（仅在有素材时使用）：
                   - [核心主体]: 名称/类别/主要焦点。
                   - [关键细节]: 提取文字字段、品牌、颜色、核心特征。
                   - [环境背景]: 场景、当前状态。
                   - [一句话摘要]: 对素材最核心价值的极简总结。
                """;
    }
}
