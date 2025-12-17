package com.tengjiao.douya.infra.store;

import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class PostgresStoreTest {
    @Resource
    private Store douyaDatabaseStore;

    @Test
    void putItem() {
        StoreItem item = StoreItem.of(List.of("user_data"), "123", Map.of("name", "Tom"));
        douyaDatabaseStore.putItem(item);
    }
}