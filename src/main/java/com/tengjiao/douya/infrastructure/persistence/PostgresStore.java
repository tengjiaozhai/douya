package com.tengjiao.douya.infrastructure.persistence;



import com.alibaba.cloud.ai.graph.store.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * PostgreSQL implementation of Store
 * Custom implementation to handle PostgreSQL dialect differences (e.g., INSERT
 * ON CONFLICT)
 */
@Slf4j
public class PostgresStore implements Store {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresStore(DataSource dataSource, String tableName) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tableName = tableName;
        initTable();
    }

    private void initTable() {
        // 1. 尝试创建目标表 (如果完全不存在)
        // 注意：新结构增加了 id 作为主键，原有的 (namespace, access_key) 转为唯一约束
        String createTableSql = String.format("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id BIGSERIAL PRIMARY KEY,
                        namespace text NOT NULL,
                        access_key text NOT NULL,
                        value jsonb,
                        created_at timestamp DEFAULT now(),
                        updated_at timestamp DEFAULT now(),
                        UNIQUE (namespace, access_key)
                    )
                """, tableName);
        try {
            jdbcTemplate.execute(createTableSql);
            
            // 2. 迁移逻辑：针对旧表进行增量升级
            // 检查 id 列是否存在 (注意 table_name 在 info schema 中通常为小写)
            String targetTable = tableName.toLowerCase();
            String checkIdSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'id'";
            Integer count = jdbcTemplate.queryForObject(checkIdSql, Integer.class, targetTable);
            
            if (count == null || count == 0) {
                log.info("检测到旧版表结构 [{}], 正在启动平滑迁移...", tableName);
                
                // a. 移除旧的复合主键约束 (PG 默认名为 table_pkey)
                jdbcTemplate.execute(String.format("ALTER TABLE %s DROP CONSTRAINT IF EXISTS %s_pkey", tableName, tableName));
                
                // b. 添加自增 ID 列并设为主键
                jdbcTemplate.execute(String.format("ALTER TABLE %s ADD COLUMN id BIGSERIAL PRIMARY KEY", tableName));
                
                // c. 将原来的逻辑主键补充为唯一约束，以维持 ON CONFLICT 逻辑
                jdbcTemplate.execute(String.format("ALTER TABLE %s ADD CONSTRAINT %s_ns_key_unique UNIQUE (namespace, access_key)", tableName, tableName));
                
                log.info("表结构迁移成功: [{}] 已升级为带 id 主键的模型。", tableName);
            }
        } catch (Exception e) {
            log.error("初始化或迁移数据库表 " + tableName + " 失败", e);
        }
    }

    private String serializeNamespace(List<String> namespace) {
        try {
            return objectMapper.writeValueAsString(namespace);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize namespace", e);
        }
    }

    private List<String> deserializeNamespace(String namespaceJson) {
        try {
            return objectMapper.readValue(namespaceJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize namespace", e);
        }
    }

    private String serializeValue(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize value", e);
        }
    }

    private Map<String, Object> deserializeValue(String valueJson) {
        try {
            return objectMapper.readValue(valueJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize value", e);
        }
    }

    @Override
    public Optional<StoreItem> getItem(List<String> namespace, String key) {
        String sql = String.format("SELECT * FROM %s WHERE namespace = ? AND access_key = ?", tableName);
        String nsJson = serializeNamespace(namespace);

        try {
            List<StoreItem> items = jdbcTemplate.query(sql, new RowMapper<StoreItem>() {
                @Override
                public StoreItem mapRow(ResultSet rs, int rowNum) throws SQLException {
                    String valJson = rs.getString("value");
                    Map<String, Object> valMap = deserializeValue(valJson);
                    // StoreItem.of(namespace, key, value)
                    return StoreItem.of(namespace, key, valMap);
                }
            }, nsJson, key);

            return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
        } catch (Exception e) {
            log.error("Error getting item", e);
            return Optional.empty();
        }
    }

    @Override
    public void putItem(StoreItem item) {
        String nsJson = serializeNamespace(item.getNamespace());
        String valJson = serializeValue(item.getValue());

        String sql = String.format("""
                    INSERT INTO %s (namespace, access_key, value, updated_at)
                    VALUES (?, ?, CAST(? AS jsonb), now())
                    ON CONFLICT (namespace, access_key)
                    DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, tableName);

        jdbcTemplate.update(sql, nsJson, item.getKey(), valJson);
    }

    @Override
    public boolean deleteItem(List<String> namespace, String key) {
        String nsJson = serializeNamespace(namespace);
        String sql = String.format("DELETE FROM %s WHERE namespace = ? AND access_key = ?", tableName);
        int update = jdbcTemplate.update(sql, nsJson, key);
        return update > 0;
    }

    @Override
    public void clear() {
        jdbcTemplate.update("DELETE FROM " + tableName);
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public long size() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public List<String> listNamespaces(NamespaceListRequest request) {
        return Collections.emptyList();
    }

    @Override
    public StoreSearchResult searchItems(StoreSearchRequest request) {
        String nsJson = serializeNamespace(request.getNamespace());
        String keyPrefix = request.getFilter().get("key_prefix") != null ? (String) request.getFilter().get("key_prefix") : "";
        
        String sql = String.format("SELECT * FROM %s WHERE namespace = ? AND access_key LIKE ? ORDER BY id ASC", tableName);
        
        try {
            List<StoreItem> items = jdbcTemplate.query(sql, (rs, rowNum) -> {
                String key = rs.getString("access_key");
                String valJson = rs.getString("value");
                Map<String, Object> valMap = deserializeValue(valJson);
                return StoreItem.of(request.getNamespace(), key, valMap);
            }, nsJson, keyPrefix + "%");

            return StoreSearchResult.of(items, items.size(), 0, Math.max(1, items.size()));
        } catch (Exception e) {
            log.error("Error searching items by prefix", e);
            return StoreSearchResult.of(Collections.emptyList(), 0, 0, 10);
        }
    }

    /**
     * 自定义辅助方法：获取指定用户的所有存储项 (通过前缀查询)
     */
    public List<StoreItem> listItemsByPrefix(List<String> namespace, String keyPrefix) {
        StoreSearchRequest request = StoreSearchRequest.builder()
            .namespace(namespace)
            .filter(Map.of("key_prefix", keyPrefix))
            .build();
        StoreSearchResult result = searchItems(request);
        return result != null ? result.getItems() : Collections.emptyList();
    }

    /**
     * 按时间区间查询存储项
     * @param namespace 命名空间
     * @param keyPrefix 用户 ID 等 Key 前缀 (可选)
     * @param startTime 开始时间
     * @param endTime 结束时间
     */
    public List<StoreItem> listItemsByTimeRange(List<String> namespace, String keyPrefix, Date startTime, Date endTime) {
        String nsJson = serializeNamespace(namespace);
        String sql = String.format("""
            SELECT * FROM %s 
            WHERE namespace = ? 
            AND access_key LIKE ? 
            AND created_at BETWEEN ? AND ?
            ORDER BY id ASC
        """, tableName);

        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                String key = rs.getString("access_key");
                String valJson = rs.getString("value");
                Map<String, Object> valMap = deserializeValue(valJson);
                return StoreItem.of(namespace, key, valMap);
            }, nsJson, keyPrefix + "%", startTime, endTime);
        } catch (Exception e) {
            log.error("Error searching items by time range", e);
            return Collections.emptyList();
        }
    }
}
