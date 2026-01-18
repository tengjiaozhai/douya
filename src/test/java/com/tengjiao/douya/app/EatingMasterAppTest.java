package com.tengjiao.douya.app;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EatingMasterAppTest {

    @Resource
    private EatingMasterApp eatingMasterApp;

    @Test
    void ask() {
        String ask = eatingMasterApp.ask("I want to eat a pizza,how to make it");
        System.out.println(ask);
    }

}
