package com.tengjiao.douya;



import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DouyaApplication {

    public static void main(String[] args) {
        // 解决 java.io.IOException: chunked transfer encoding, state: READING_LENGTH
        // 必须在 HttpClient 初始化前设置，因此放在 main 方法最开始
        // 参考 https://github.com/spring-projects/spring-ai/issues/2740
        System.setProperty("jdk.httpclient.keepalive.timeout", "10");
        System.setProperty("jdk.httpclient.keepalive.timeout.h2", "10");
        SpringApplication.run(DouyaApplication.class, args);
    }

}
