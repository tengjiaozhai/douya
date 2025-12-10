package com.tengjiao.douya.interceptors;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.tengjiao.douya.app.UserVectorApp;
import lombok.Data;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * 用户偏好适配
 *
 * @author tengjiao
 * @since 2025-12-10 18:31
 */

public class UserPreferInterceptors extends ModelInterceptor {
    private final UserVectorApp userVectorApp;

    public UserPreferInterceptors(UserVectorApp userVectorApp) {
        this.userVectorApp = userVectorApp;
    }

    @Data
    public static class UserPreferences {
        private String communicationStyle;
        private String language;
        private List<String> interests;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
        // 从运行时上下文获取用户ID
        String userId = getUserIdFromContext(request);

        // 从存储加载用户偏好
        UserPreferences prefs = userVectorApp.getPreferences(userId);

        // 构建个性化提示
        String personalizedPrompt = buildPersonalizedPrompt(prefs);

        // 更新系统消息（参考 TodoListInterceptor 的实现方式）
        SystemMessage enhancedSystemMessage;
        if (request.getSystemMessage() == null) {
            enhancedSystemMessage = new SystemMessage(personalizedPrompt);
        } else {
            enhancedSystemMessage = new SystemMessage(
                request.getSystemMessage().getText() + " " + personalizedPrompt
            );
        }

        ModelRequest updatedRequest = ModelRequest.builder(request)
            .systemMessage(enhancedSystemMessage)
            .build();

        return next.call(updatedRequest);
    }

    private String getUserIdFromContext(ModelRequest request) {
        // 从请求上下文提取用户ID
        return "user_001"; // 简化示例
    }

    private String buildPersonalizedPrompt(UserPreferences prefs) {
        StringBuilder prompt = new StringBuilder("你是一个有用的助手。");

        if (prefs.getCommunicationStyle() != null) {
            prompt.append("沟通风格：").append(prefs.getCommunicationStyle());
        }

        if (prefs.getLanguage() != null) {
            prompt.append("使用语言：").append(prefs.getLanguage());
        }

        if (!prefs.getInterests().isEmpty()) {
            prompt.append("用户兴趣：").append(String.join(", ", prefs.getInterests()));
        }

        return prompt.toString();
    }

    @Override
    public String getName() {
        return "PersonalizedPromptInterceptor";
    }
}
