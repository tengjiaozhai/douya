# Douya (豆芽) - 智能体后端服务

## 简介

Douya 是一个基于 Spring Boot 开发的智能体（AI Agent）后端服务。它集成了 **Spring AI Alibaba** 框架，利用阿里云 DashScope（通义千问）提供强大的大模型能力，并预置了 **飞书 (Feishu/Lark)** 开放平台的集成能力，旨在构建高效的企业级 AI 应用。

## 技术栈

- **核心框架**: Spring Boot 3.5.8
- **AI 框架**: Spring AI Alibaba (Agent Framework)
- **大模型服务**: Alibaba DashScope
- **向量数据库**: Chroma Vector Store
- **嵌入模型**: DashScope Embedding (qwen2.5-vl-embedding)
- **API 文档**: Knife4j (Swagger/OpenAPI 3)
- **工具库**: Lombok, Feishu OAPI SDK
- **构建工具**: Maven
- **JDK 版本**: Java 21

## 快速开始

### 1. 环境准备

- JDK 21 或更高版本
- Maven 3.x

### 2. 配置

项目默认使用 `dev` 环境配置。请确保在 `src/main/resources/application-dev.yml` 中配置正确的密钥信息：

```yaml
spring:
  ai:
    dashscope:
      api-key: <YOUR_DASHSCOPE_API_KEY>
    vectorstore:
      chroma:
        collection-name: douya_collection
        client:
          host: http://localhost
          port: 8000

feishu:
  app-id: <YOUR_FEISHU_APP_ID>
  app-secret: <YOUR_FEISHU_APP_SECRET>
```

**注意**: 使用向量存储功能前，需要先启动 Chroma 服务。可以使用 Docker 快速启动：

```bash
docker run -d -p 8000:8000 chromadb/chroma
```

### 3. 启动项目

在项目根目录下运行：

```bash
mvn spring-boot:run
```

### 4. 访问服务

项目启动后，默认运行在 `8787` 端口。

- **API 文档 (Knife4j)**: [http://localhost:8787/api/doc.html](http://localhost:8787/api/doc.html)
- **健康检查**: [http://localhost:8787/api/douya/hello](http://localhost:8787/api/douya/hello)

## 项目结构

```
douya
├── src/main/java/com/tengjiao/douya
│   ├── app
│   │   └── UserVectorApp.java      # 用户向量服务（向量存储与搜索）
│   ├── config
│   │   ├── ChromaConfig.java       # Chroma 向量数据库配置
│   │   ├── ChromaProperties.java   # Chroma 配置属性
│   │   └── FeishuConfig.java       # 飞书 WebSocket 配置
│   ├── controller
│   │   ├── AiController.java       # AI 相关接口
│   │   └── FeishuController.java   # 飞书 Token 接口
│   ├── service
│   │   ├── FeishuService.java      # 飞书服务接口
│   │   └── impl
│   │       └── FeishuServiceImpl.java # 飞书服务实现 (Token 缓存)
│   └── DouyaApplication.java       # 启动类
├── src/main/resources
│   ├── application.yml             # 主配置
│   └── application-dev.yml         # 开发环境配置
└── pom.xml                         # Maven 依赖配置
```

## 功能特性

### 飞书集成 (Feishu Integration)

项目已预置飞书开放平台集成能力，支持：

1.  **WebSocket 长连接**:
    - 自动建立与飞书的长连接，无需公网 IP 即可接收回调。
    - 已实现 `P2MessageReceiveV1` (私聊消息) 和 `P2CardActionTrigger` (卡片交互) 等事件的监听示例。
2.  **Token 管理**:
    - **App Access Token**: 实现了 `app_access_token` 的获取与本地缓存（自动刷新）。
    - **Tenant Access Token**: 实现了 `tenant_access_token` 的获取与本地缓存（自动刷新）。
    - 接口地址:
      - App Token: `POST /api/douya/feishu/token`
      - Tenant Token: `POST /api/douya/feishu/tenant-token`
3.  **消息发送**:
    - 支持发送文本、富文本、卡片等多种类型的消息给指定用户或群组。
    - 接口地址: `POST /api/douya/feishu/message/send?receive_id_type=open_id`

### 向量存储 (Vector Store Integration)

项目集成了 **Chroma 向量数据库**，结合阿里云 DashScope 的 `qwen2.5-vl-embedding` 模型，提供强大的向量存储和语义搜索能力：

1.  **用户隔离的向量存储**:
    - 通过 `UserVectorApp` 服务实现基于 `userId` 的数据隔离。
    - 每个用户的向量数据独立存储，互不干扰。
    - 自动添加时间戳元数据，便于数据管理。
2.  **相似度搜索**:

    - 支持语义相似度搜索，适用于"吃饭大师"等场景。
    - 可配置 Top-K 结果数量（默认 5 条）。
    - 可配置相似度阈值（默认 0.7）。
    - 自动按 `userId` 过滤，确保数据隔离。

3.  **配置说明**:

    ```yaml
    spring:
      ai:
        dashscope:
          api-key: <YOUR_DASHSCOPE_API_KEY>
        vectorstore:
          chroma:
            collection-name: douya_collection
            client:
              host: http://localhost
              port: 8000
    ```

4.  **使用示例**:

    ```java
    @Autowired
    private UserVectorApp userVectorApp;

    // 存储向量数据
    List<Document> documents = List.of(
        new Document("川菜馆推荐：麻辣香锅很好吃"),
        new Document("粤菜馆推荐：早茶很正宗")
    );
    userVectorApp.addDocuments(documents, "user123");

    // 相似度搜索
    List<Document> results = userVectorApp.searchSimilar("我想吃辣的", "user123");
    ```

## 开发者

- **Author**: tengjiao
- **GitHub**: [https://github.com/tengjiao](https://github.com/tengjiao)
