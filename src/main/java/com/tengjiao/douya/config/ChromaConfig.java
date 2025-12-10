package com.tengjiao.douya.config;


import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.Resource;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chroma配置
 *
 * @author tengjiao
 * @since 2025-12-10 16:05
 */
@Configuration
public class ChromaConfig {
    @Resource
    private ChromaProperties chromaProperties;

    @Bean
    public RestClient.Builder builder() {
        return RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
    }


    @Bean
    public ChromaApi chromaApi(RestClient.Builder restClientBuilder) {
        ChromaProperties.Client client = chromaProperties.getClient();
        String chromaUrl = client.getHost() + ":" + client.getPort();
        return new ChromaApi(chromaUrl, restClientBuilder, new ObjectMapper());
    }

    @Bean
    public VectorStore chromaVectorStore(EmbeddingModel dashscopeEmbeddingModel, ChromaApi chromaApi) {
        return ChromaVectorStore.builder(chromaApi, dashscopeEmbeddingModel)
                .collectionName(chromaProperties.getCollectionName())
                .databaseName("SpringAiDatabase")  // Chroma v2 需要的 database 参数
                .tenantName("SpringAiTenant")      // Chroma v2 需要的 tenant 参数
                .initializeSchema(true)
                .build();
    }
}
