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
4.  **性能与可靠性优化**:
    - **异步处理**: 使用 Java 21 虚拟线程异步处理耗时的大模型请求，确保在飞书要求的 3 秒内完成事件响应，避免重试。
    - **幂等去重**: 内置消息 ID 缓存（基于 LRU 策略），自动过滤飞书因网络抖动产生的重复推送，防止大模型重复思考。
    - **交互体验**: 采用“先响应、后思考、再回复”的模式，第一时间给用户反馈，消除等待焦虑。

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
    - **存储优化**: 采用 `Set` (LinkedHashSet) 结构存储，自动过滤重复记录，节省资源并保持顺序。
3.  **使用方式**:
    - **对话接口**: `GET /api/douya/chat?message=我喜欢吃火锅&userId=user_001`
      - 系统会在后台自动分析并记录"火锅"这一偏好。
    - **查询偏好**: `GET /api/douya/preferences?userId=user_001`
      - 返回该用户所有已记录的偏好列表。

### 混合记忆管理 (Hybrid Memory Management)

支持短期记忆与长期记忆的智能流转与知识转化：

1.  **短期上下文管理**: 自动维护最近 **10 条** 消息的活跃上下文，平衡响应精度与上下文成本。
2.  **持久化归档**: 超出阈值的历史消息自动存入持久化存储（`DatabaseStore`），支持全量历史追溯。
3.  **异步总结批处理 (Token 优化)**:
    - **分批触发**: 仅在历史记录每积攒满 **10 条** 时才触发一次总结，显著降低大模型调用频率及 Token 消耗。
    - **语义总结**: 自动将散乱的对话压缩为精简摘要，保留核心地理、意图及决策信息。
    - **向量化知识库**: 使用 `UserVectorApp` 将摘要存入 Chroma，构建用户专属的 RAG 知识源。
    - **无感执行**: 基于 Java 21 虚拟线程异步完成，零延迟感。

### 用户偏好自动注入 (User Preference Interceptor)

通过 `UserPreferInterceptors` 实现“越聊越懂你”的个性化体验：

1.  **静默拦截**: 拦截器在请求发送给大模型前，自动从数据库加载该用户的长期偏好标签。
2.  **上下文增强**: 动态将提取的偏好（如“少辣”、“不吃香菜”）追加到系统提示词中。
3.  **决策辅助**: 使 Agent 在推荐方案时能自动避开用户忌口，无需用户每次重复输入。

### Agent 配置与性能优化

1.  **结构化提示词**: 将 `SYSTEM_PROMPT` 拆分为 `systemPrompt` (角色设定) 与 `instruction` (任务指令)，使 Agent 性格更鲜明、业务逻辑更易维护。
2.  **响应长度提升**: 将模型最大输出长度 (`maxTokens`) 统一提升至 **2000**，确保详细的长图文建议能完整呈现。

### 多模型冲突解决

由于项目中同时引入了 `spring-ai-alibaba-starter-dashscope` 和 `spring-ai-starter-model-openai`，会导致容器中存在多个 `EmbeddingModel` 实例，从而引发 `VectorStore` 自动配置冲突。

**解决方案：**

1.  **明确指定 Bean**: 在 `ChromaConfig` 中注入 `EmbeddingModel` 时，使用 `@Qualifier("dashscopeEmbeddingModel")` 明确指定使用 DashScope 的模型。
2.  **禁用冲突配置**: 在 `application.yml` 中设置 `spring.ai.openai.embedding.enabled: false`，禁用 OpenAI 的 Embedding 自动配置，确保全局只有一个主嵌入模型（DashScope）。

### 飞书消息资源处理

支持对飞书富文本消息（如图片）的自动化处理：

- **图片自动下载**: 当接收到 `image` 类型消息时，系统会通过 `FeiShuGetMessageResourceUtils` 自动将其下载并保存到项目根目录下的 `src/main/resources/temp` 路径。
- **动态路径计算**: 使用 `ApplicationHome` 动态获取环境目录，确保在不同部署环境下均能正确识别资源存储路径。

## 开发者

- **Author**: tengjiao
- **GitHub**: [https://github.com/tengjiao](https://github.com/tengjiao)

## 更新日志

### 2025-12-22
- **优化 Agent 指令**: 在 `EatingMasterApp` 中明确了联网搜索的指令，解决了模型因系统提示词限制而拒绝使用搜索功能的幻觉问题。

