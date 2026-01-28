package com.tengjiao.douya.application.interceptors;



import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户偏好注入拦截器
 * 在请求大模型之前，自动加载用户长期偏好并追加到系统提示词中
 *
 * @author tengjiao
 * @since 2025-12-22
 */
@Slf4j
public class UserPreferInterceptors extends ModelInterceptor {

    private final Store douyaDatabaseStore;
    private final String userId;

    public UserPreferInterceptors(Store douyaDatabaseStore, String userId) {
        this.douyaDatabaseStore = douyaDatabaseStore;
        this.userId = userId;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
        if (userId == null) {
            return next.call(request);
        }

        // 1. 加载用户偏好
        Set<String> preferences = loadPreferences(userId);
        if (preferences.isEmpty()) {
            return next.call(request);
        }

        // 2. 构建偏好提示词
        String prefPrompt = "\n\n[已知用户偏好信息]:\n" + preferences.stream()
                .map(p -> "- " + p)
                .collect(Collectors.joining("\n"));

        // 3. 增强系统消息
        SystemMessage enhancedSystemMessage;
         if (request.getSystemMessage() == null) {
            enhancedSystemMessage = new SystemMessage(prefPrompt);
        } else {
            enhancedSystemMessage = new SystemMessage(
                    request.getSystemMessage().getText() + prefPrompt
            );
        }

        // 4. 创建新请求并继续执行
        ModelRequest updatedRequest = ModelRequest.builder(request)
                .systemMessage(enhancedSystemMessage)
                .build();

        log.info("[Interceptor] 已为用户 {} 注入偏好信息，共 {} 条", userId, preferences.size());
        return next.call(updatedRequest);
    }

    /**
     * 加载用户偏好
     *
     * @param userId 用户 ID
     * @return 偏好列表
     */
    private Set<String> loadPreferences(String userId) {
        Optional<StoreItem> prefsOpt = douyaDatabaseStore.getItem(List.of("user_data"), userId + "_preferences");
        if (prefsOpt.isPresent()) {
            Map<String, Object> prefsData = prefsOpt.get().getValue();
            Object items = prefsData.get("items");
            if (items instanceof Collection) {
                // 转换 Collection<Object> 到 Set<String>
                Collection<?> rawItems = (Collection<?>) items;
                return rawItems.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        }
        return new LinkedHashSet<>();
    }

    @Override
    public String getName() {
        return "UserPreferenceInterceptor";
    }
}
