package com.tengjiao.douya.infra.store;

import com.alibaba.cloud.ai.graph.store.NamespaceListRequest;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
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
 * Custom implementation to handle PostgreSQL dialect differences (e.g., INSERT ON CONFLICT)
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
        String sql = String.format("""
            CREATE TABLE IF NOT EXISTS %s (
                namespace text NOT NULL,
                access_key text NOT NULL,
                value jsonb,
                created_at timestamp DEFAULT now(),
                updated_at timestamp DEFAULT now(),
                PRIMARY KEY (namespace, access_key)
            )
        """, tableName);
        try {
            jdbcTemplate.execute(sql);
            log.info("Initialized PostgresStore table: {}", tableName);
        } catch (Exception e) {
            log.error("Failed to initialize table " + tableName, e);
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
            return objectMapper.readValue(namespaceJson, new TypeReference<List<String>>() {});
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
            return objectMapper.readValue(valueJson, new TypeReference<Map<String, Object>>() {});
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
            VALUES (?, ?, ?::jsonb, now())
            ON CONFLICT (namespace, access_key) 
            DO UPDATE SET value = EXCLUDED.value, updated_at = now()
        """, tableName);
        
        jdbcTemplate.update(sql, nsJson, item.getKey(), valJson);
    }

    @Override
    public void deleteItem(List<String> namespace, String key) {
        String nsJson = serializeNamespace(namespace);
        String sql = String.format("DELETE FROM %s WHERE namespace = ? AND access_key = ?", tableName);
        jdbcTemplate.update(sql, nsJson, key);
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
    public int size() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public List<List<String>> listNamespaces(NamespaceListRequest request) {
        return Collections.emptyList();
    }

    @Override
    public List<StoreItem> searchItems(StoreSearchRequest request) {
        return Collections.emptyList();
    }
}
