package com.tengjiao.douya.service.impl;

import com.tengjiao.douya.service.OssService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OssServiceImplTest {
    @Resource
    private OssService ossService;

    @Test
    void cleanupRedundantImages() {
        String prefix = "documents/";
        List<String> objects = ossService.listObjects(prefix);
        System.out.println("开始清理 OSS 冗余文件，总计扫描到: " + objects.size() + " 个文件");

        int renamedCount = 0;
        int deletedCount = 0;
        int skippedCount = 0;

        // 正则匹配 pattern: documents/{docName}/{docName}_page{page}_img{index}.png
        // 捕获组: 1: docDir, 2: fileNamePrefix, 3: pageNum, 4: imgIndex
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^documents/([^/]+)/(.+)_page(\\d+)_img(\\d+)\\.png$");

        for (String key : objects) {
            java.util.regex.Matcher matcher = pattern.matcher(key);
            if (matcher.matches()) {
                String docDir = matcher.group(1);
                String namePrefix = matcher.group(2);
                int pageNum = Integer.parseInt(matcher.group(3));
                String imgIndex = matcher.group(4);

                if (pageNum == 1) {
                    // 重命名逻辑: 复制 + 删除
                    String newKey = String.format("documents/%s/%s_img%s.png", docDir, namePrefix, imgIndex);
                    try {
                        ossService.copyObject(key, newKey);
                        ossService.deleteObject(key);
                        renamedCount++;
                        System.out.println("重命名完成: " + key + " -> " + newKey);
                    } catch (Exception e) {
                        System.err.println("重命名失败: " + key + ", error: " + e.getMessage());
                    }
                } else {
                    // 删除逻辑
                    try {
                        ossService.deleteObject(key);
                        deletedCount++;
                        System.out.println("删除冗余完成: " + key);
                    } catch (Exception e) {
                        System.err.println("删除失败: " + key + ", error: " + e.getMessage());
                    }
                }
            } else {
                skippedCount++;
            }
        }

        System.out.println("--------------------------------------");
        System.out.println("清理完成统计:");
        System.out.println("重命名个数: " + renamedCount);
        System.out.println("删除冗余个数: " + deletedCount);
        System.out.println("跳过个数: " + skippedCount);
        System.out.println("--------------------------------------");
    }
}
