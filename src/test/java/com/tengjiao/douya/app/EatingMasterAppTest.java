package com.tengjiao.douya.app;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EatingMasterAppTest {

    @Resource
    private EatingMasterApp eatingMasterApp;
    @Resource
    private ChatModel eatingMasterModel;

    @Test
    void ask() {
        String ask = eatingMasterApp.ask("I want to eat a pizza,how to make it");
        System.out.println(ask);
    }


    @Test
    void testMcp() throws GraphRunnerException {

        ReactAgent agent = ReactAgent.builder()
            .name("my_agent")
            .model(eatingMasterModel)
            .build();
        AssistantMessage assistantMessage = agent.call("搜索西雅图的酒店");
        System.out.println(assistantMessage.getText());
    }
}
