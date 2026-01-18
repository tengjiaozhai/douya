package com.tengjiao.douya.service.impl;

import com.tengjiao.douya.service.OssService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OssServiceImplTest {
    @Resource
    private OssService ossService;

    @Test
    void doesObjectExist() {
        System.out.println(ossService.uploadFile("maidangdang.jpg","/Users/shenmingjie/Pictures/WechatIMG50.jpg"));
    }
}