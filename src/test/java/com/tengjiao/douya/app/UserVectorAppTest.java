package com.tengjiao.douya.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UserVectorAppTest {

    @Resource
    private UserVectorApp userVectorApp;

    @Test
    void addDocuments() throws InterruptedException {
        List<Document> documents = List.of(
            new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!"),
            new Document("The World is Big and Salvation Lurks Around the Corner"),
            new Document("You walk forward facing the past and you turn back toward the future."));
        
        userVectorApp.addDocuments(documents, "tengjiaozhai");
        
        // 等待向量数据库索引完成
        System.out.println("等待向量索引完成...");
        Thread.sleep(2000); // 等待2秒
        
        System.out.println("文档添加成功！");
    }

    @Test
    void searchSimilar() throws InterruptedException {
        System.out.println("=== 开始测试相似度搜索 ===");
        
        // 先添加文档
        List<Document> documents = List.of(
            new Document("我喜欢吃川菜，特别是麻辣火锅"),
            new Document("粤菜很清淡，适合养生"),
            new Document("日本料理很精致，寿司很好吃"));
        
        System.out.println("准备添加 " + documents.size() + " 个文档...");
        
        try {
            userVectorApp.addDocuments(documents, "testUser");
            System.out.println("✓ 文档添加成功");
        } catch (Exception e) {
            System.err.println("✗ 文档添加失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
        
        // 等待索引
        System.out.println("等待 3 秒让向量数据库完成索引...");
        Thread.sleep(3000);
        
        // 先尝试获取所有文档
        System.out.println("\n--- 尝试获取所有文档 ---");
        try {
            List<Document> allDocs = userVectorApp.getAllDocuments(100, "testUser");
            System.out.println("找到文档数量: " + allDocs.size());
            for (Document doc : allDocs) {
                System.out.println("  - ID: " + doc.getId());
                System.out.println("    内容: " + doc.getText());
                System.out.println("    元数据: " + doc.getMetadata());
            }
        } catch (Exception e) {
            System.err.println("获取所有文档失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 搜索相似文档
        System.out.println("\n--- 执行相似度搜索 ---");
        System.out.println("查询词: 辣的菜");
        System.out.println("用户ID: testUser");
        
        try {
            List<Document> results = userVectorApp.searchSimilar("辣的菜", "testUser");
            
            System.out.println("搜索结果数量: " + results.size());
            for (Document doc : results) {
                System.out.println("---");
                System.out.println("内容: " + doc.getText());
                System.out.println("元数据: " + doc.getMetadata());
            }
            
            assertFalse(results.isEmpty(), "应该返回搜索结果");
        } catch (Exception e) {
            System.err.println("搜索失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    void getAllDocuments() throws InterruptedException {
        // 先添加一些文档
        List<Document> documents = List.of(
            new Document("测试文档1"),
            new Document("测试文档2"),
            new Document("测试文档3"));
        
        userVectorApp.addDocuments(documents, "tengjiaozhai");
        
        // 等待索引
        Thread.sleep(2000);
        
        // 获取所有文档
        List<Document> allDocs = userVectorApp.getAllDocuments(100, "tengjiaozhai");
        
        System.out.println("找到文档数量: " + allDocs.size());
        for (Document document : allDocs) {
            System.out.println("元数据: " + document.getMetadata());
            System.out.println("内容: " + document.getText());
            System.out.println("---");
        }
        
        assertFalse(allDocs.isEmpty(), "应该返回文档列表");
    }
}
