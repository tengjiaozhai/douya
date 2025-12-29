package com.tengjiao.douya.config;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class ModelConfigTest {
    @Resource
    private ChatModel readUnderstandModel;

    @Test
    void readUnderstandModel() {
        String call = readUnderstandModel.call("请用中文描述一张图片。");
        System.out.println(call);
    }
}