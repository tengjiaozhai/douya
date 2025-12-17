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

## 记忆存储 (Memory Configuration)

本项目主要使用 `Spring AI Alibaba Graph` 提供的 `Store` 接口来管理 Agent 的状态（State）和记忆（Memory）。目前支持以下四种存储介质，其优劣对比及选择建议如下：

| 存储方案          | 类型    | 优点                                                  | 缺点                                                | 适用场景                              | 推荐指数          |
| :---------------- | :------ | :---------------------------------------------------- | :-------------------------------------------------- | :------------------------------------ | :---------------- |
| **MemoryStore**   | 内存    | 🚀 **极速**、零配置、无依赖                           | ❌ **易失性** (重启即丢)、占用 JVM 内存、不支持集群 | **开发/测试/演示** (当前默认)         | ⭐⭐⭐ (Dev)      |
| **RedisStore**    | KV 缓存 | ⚡ **高性能**、支持持久化、支持集群共享、TTL 机制完善 | ⚠️ 需部署 Redis 服务                                | **生产环境首选** (Session/State 管理) | ⭐⭐⭐⭐⭐ (Prod) |
| **DatabaseStore** | SQL     | 🛡️ **结构化**、数据强一致、易于审计/分析              | 📉 读写性能略低、Schema 变更略繁琐                  | 需与业务数据强关联、长期归档          | ⭐⭐⭐            |
| **MongoStore**    | 文档    | 📝 **灵活** (Schema-less)、适合存复杂对象             | ⚠️ 需部署 Mongo、运维成本增加                       | 存储大规模非结构化历史数据            | ⭐⭐⭐            |

### 环境与依赖要求

使用非 `MemoryStore` 方案时，需要准备相应的外部服务并添加 Maven 依赖：

- **RedisStore**:
  - **服务**: 需安装 Redis Server (推荐 6.0+)。
  - **依赖**: `spring-boot-starter-data-redis`。
- **调整 DeepSeek 集成**: 由于 Spring AI 1.0.0-M6 不包含 `spring-ai-starter-model-deepseek`，已将其替换为 `spring-ai-openai-spring-boot-starter`，并通过 OpenAI 兼容模式连接 DeepSeek API。相关配置已在 `ModelConfig.java` 中更新。
- **自定义 PostgresStore**: 解决了 `DatabaseStore` 在 PostgreSQL 下使用 MySQL 语法 (`ON DUPLICATE KEY UPDATE`) 导致的语法错误问题。新增 `PostgresStore` 实现类，采用 `INSERT ... ON CONFLICT` 语法适配 PostgreSQL，并在 `DataSourceConfig` 中完成了替换。
  - **初始化**: 需手动创建存储 Session/State 的数据表 (Schema)。
- **MongoStore**:
  - **服务**: 需安装 MongoDB Server。
  - **依赖**: `spring-boot-starter-data-mongodb`。

> **⚠️ 重要区分**:
> 上述要求仅针对 **Agent 记忆/状态 (Memory/State)** 存储。
>
> - 它们只需要标准的数据库功能 (KV 读写 / SQL 查询)。
> - **不需要** 安装 `Redis Stack` (RediSearch) 或 `pgvector` 等向量插件。
> - (向量插件仅在您使用 Redis/PG 替代 Chroma 作为 **向量数据库 (Vector Store)** 时才需要)。

### 方案建议

1.  **当前状态**: 为了保证项目 **快速启动 (Quick Start)** 且不依赖过多外部环境，项目默认使用 **`MemoryStore`**。这意味着重启服务后，用户的短期对话历史和临时偏好将会丢失。
2.  **生产建议**: 对于 **生产环境**，强烈建议切换为 **`RedisStore`**。
    - 它能提供毫秒级的状态读写，这对 AI 对话的响应速度至关重要。
    - 支持数据持久化，确保用户偏好不会因服务重启而丢失。
    - 原生支持分布式锁和过期策略，适合管理海量会话。

### 如何切换到 RedisStore

1.  添加 Redis 依赖到 `pom.xml`:
    ```xml
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    ```
2.  修改代码 (如 `EatingMasterApp.java`):

    ```java
    // 注入 RedisTemplate
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 替换 MemoryStore
    // MemoryStore memoryStore = new MemoryStore();
    RedisStore redisStore = new RedisStore(redisTemplate);
    ```

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

    ````

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
    ````

### 用户偏好学习 (User Preference Learning)

项目实现了基于 **DeepSeek** 模型的智能用户偏好学习功能，能够在对话过程中自动分析并提取用户的饮食偏好，实现更懂用户的个性化服务：

1.  **智能提取**:
    - 使用 `PreferenceLearningHook` 拦截 AI 回复后的流程。
    - 利用 DeepSeek 推理模型分析用户的每一条输入消息。
    - 自动提取明确表达的喜好（如"我喜欢吃辣"、"不吃香菜"等）。
2.  **长期记忆**:
    - 基于 `MemoryStore` 实现用户偏好的持久化存储。
    - 偏好数据按 `userId` 隔离存储，随用随取。
3.  **使用方式**:
    - **对话接口**: `GET /api/douya/chat?message=我喜欢吃火锅&userId=user_001`
      - 系统会在后台自动分析并记录"火锅"这一偏好。
    - **查询偏好**: `GET /api/douya/preferences?userId=user_001`
      - 返回该用户所有已记录的偏好列表。

## 开发者

- **Author**: tengjiao
- **GitHub**: [https://github.com/tengjiao](https://github.com/tengjiao)
