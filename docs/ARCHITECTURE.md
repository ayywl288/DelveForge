# 架构

Vue 工作台从 `frontend/src/main.ts` 挂载 `ProjectWorkbench`，通过同源 `/api/workbench` 访问宿主上的 `WorkbenchApplication`。控制端只监听回环地址，并校验 Host 和浏览器来源。项目预览使用独立来源。

| Maven 模块 | 职责 |
| --- | --- |
| `delveforge-domain` | 定义 Run 状态和前台占用语义，无技术依赖 |
| `delveforge-application` | 编排项目、对话、准入、恢复与上下文；定义模型、存储、工作副本和执行环境端口 |
| `delveforge-infrastructure` | 实现 SQLite、工作副本导入、Docker 执行、预览网关、模型目录、凭据和 Spring AI 适配器 |
| `delveforge-app` | Spring Boot 启动、配置、依赖装配、REST 和本机来源检查 |

Application 只依赖 Domain。Infrastructure 实现 Application 端口。App 组装两者。宿主通过有界的 SQLite 短事务串行化关键决定，提供方请求、Git 导入和容器执行都在事务外发生。每个控制数据库使用单实例锁。Flyway 只加载 `classpath:db/workbench`，已发布的工作台迁移保持可校验。项目数据和对话在设定的数据目录中。独立的旧流程数据库不参与启动。

Windows 的原始项目目录只供导入读取。工具在项目专属的 Linux 容器中使用工作副本。宿主控制 Docker 管道、模型凭据及全部执行记录；预览、出站网络和历史查询经受控网关。架构决策见 [对话工作台](decisions/0028-project-conversations-and-shared-environment.md) 和 [项目隔离运行时](decisions/0029-project-isolated-runtime.md)。

每个 Run 以独立配置调用 Spring AI `ChatModel`。工具的 JSON Schema 由应用定义，提议的准入和执行归 `ConversationAgent`。有序消息部件及厂商签名保存在宿主，HTTP 和容器查询只返回公开投影。控制数据使用 Jackson 2 保持已有 SQLite 编码兼容，HTTP 与 Spring AI 使用 Jackson 3。[模型配置与重放](decisions/0030-workbench-model-configuration-and-replay.md) · [旧流程退役](decisions/0031-retire-workflow-runtime.md)
