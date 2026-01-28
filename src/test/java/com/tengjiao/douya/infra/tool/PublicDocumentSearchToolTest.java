package com.tengjiao.douya.infra.tool;

import com.tengjiao.douya.app.UserVectorApp;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PublicDocumentSearchToolTest {
    @Resource
    private UserVectorApp userVectorApp;

    @Test
    void search() {
        PublicDocumentSearchTool publicDocumentSearchTool = new PublicDocumentSearchTool(userVectorApp);
        PublicDocumentSearchTool.Response response = publicDocumentSearchTool.search(new PublicDocumentSearchTool.Request("南瓜粥"));
        System.out.println(response.content());
    }
}
