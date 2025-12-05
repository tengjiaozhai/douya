# Douya (豆芽) - 智能体后端服务

## 简介

Douya 是一个基于 Spring Boot 开发的智能体（AI Agent）后端服务。它集成了 **Spring AI Alibaba** 框架，利用阿里云 DashScope（通义千问）提供强大的大模型能力，并预置了 **飞书 (Feishu/Lark)** 开放平台的集成能力，旨在构建高效的企业级 AI 应用。

## 技术栈

- **核心框架**: Spring Boot 3.5.8
- **AI 框架**: Spring AI Alibaba (Agent Framework)
- **大模型服务**: Alibaba DashScope
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

feishu:
  app-id: <YOUR_FEISHU_APP_ID>
  app-secret: <YOUR_FEISHU_APP_SECRET>
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
│   ├── config
│   │   └── FeishuConfig.java  # 飞书 WebSocket 配置
│   ├── controller
│   │   ├── AiController.java      # AI 相关接口
│   │   └── FeishuController.java  # 飞书 Token 接口
│   ├── service
│   │   ├── FeishuService.java     # 飞书服务接口
│   │   └── impl
│   │       └── FeishuServiceImpl.java # 飞书服务实现 (Token 缓存)
│   └── DouyaApplication.java  # 启动类
├── src/main/resources
│   ├── application.yml        # 主配置
│   └── application-dev.yml    # 开发环境配置
└── pom.xml                    # Maven 依赖配置
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

## 开发者

- **Author**: tengjiao
- **GitHub**: [https://github.com/tengjiao](https://github.com/tengjiao)
