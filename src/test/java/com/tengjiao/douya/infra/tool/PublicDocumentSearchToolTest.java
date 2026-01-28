package com.tengjiao.douya.infra.tool;

import com.tengjiao.douya.infrastructure.tool.PublicDocumentSearchTool;
import com.tengjiao.douya.infrastructure.vectorstore.UserVectorApp;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@SpringBootTest
class PublicDocumentSearchToolTest {

    @Resource
    private UserVectorApp userVectorApp;

    @Resource
    private ChatModel douBaoTransitDeepseek;

    @Test
    void search() {
        PublicDocumentSearchTool publicDocumentSearchTool = new PublicDocumentSearchTool(userVectorApp, douBaoTransitDeepseek);
        PublicDocumentSearchTool.Response response = publicDocumentSearchTool.search(new PublicDocumentSearchTool.Request("南瓜粥"));
        System.out.println(response.content());
    }
}
