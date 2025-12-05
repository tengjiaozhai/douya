package com.tengjiao.douya.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
