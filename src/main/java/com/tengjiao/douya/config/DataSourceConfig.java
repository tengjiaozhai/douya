package com.tengjiao.douya.config;

import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.DatabaseStore;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 数据库配置
 *
 * @author tengjiao
 * @since 2025-12-11 13:30
 */
@Slf4j
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    public Store douyaDatabaseStore(DataSource dataSource) {
        log.info("Initializing DatabaseStore with table 'douya_store' using PostgreSQL");
        // 初始化 DatabaseStore，使用 douya_store 表名
        // 这里的 dataSource 已经配置了 currentSchema=douya，所以表会创建在 douya schema 下
        return new DatabaseStore(dataSource, "douya_store");
    }
}
