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
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        
        // 确保设置了模式
        if (dataSource.getSchema() == null) {
            dataSource.setSchema("douya");
        }
        
        return dataSource;
    }

    @Bean
    public Store douyaDatabaseStore(DataSource dataSource) {
        log.info("Initializing DatabaseStore with table 'douya_store' using PostgreSQL");
        
        // 确保数据库模式存在
        try {
            ensureSchemaExists(dataSource);
        } catch (Exception e) {
            log.error("Failed to ensure schema exists", e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
        
        // 初始化 DatabaseStore，使用 douya_store 表名
        return new DatabaseStore(dataSource, "douya_store");
    }
    
    /**
     * 确保数据库模式存在
     * @param dataSource 数据源
     */
    private void ensureSchemaExists(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            // 检查模式是否存在
            var schemaExistsQuery = """
                SELECT EXISTS(
                    SELECT 1 FROM information_schema.schemata 
                    WHERE schema_name = 'douya'
                )
                """;
            
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(schemaExistsQuery)) {
                
                if (resultSet.next() && !resultSet.getBoolean(1)) {
                    // 如果模式不存在，则创建它
                    log.info("Creating schema 'douya'");
                    try (var createStatement = connection.createStatement()) {
                        createStatement.execute("CREATE SCHEMA douya");
                    }
                } else {
                    log.info("Schema 'douya' already exists");
                }
            }
        } catch (Exception e) {
            log.error("Error ensuring schema exists", e);
            throw new RuntimeException("Failed to ensure database schema exists", e);
        }
    }
}