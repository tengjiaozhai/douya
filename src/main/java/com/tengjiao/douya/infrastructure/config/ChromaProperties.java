package com.tengjiao.douya.infrastructure.config;



import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "spring.ai.vectorstore.chroma")
@Data
public class ChromaProperties {
    private String collectionName;
    private Client client = new Client();

    @Data
    public static class Client {
        private String host;
        private Integer port;
    }
}
