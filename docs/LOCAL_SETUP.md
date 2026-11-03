# 本地安装与启动

Agent 当前工具协议为 **v7**，包含固定 Node 22.16.0、npm 10.9.2、pnpm 12.9.1。
升级后重新运行启动器构建和核验当前镜像；旧 v6 及更早镜像会在传入源码前被拒绝。
v7 增加项目脚本及 Node/Vitest 报告采集；v6 已引入绑定摘要的资源能力与执行投影；正式 Git 保留完整原字节，未物化的敏感资源不会发送给项目进程。
前端检查的脚本、锁文件及隔离限制见 [受控前端检查](AGENT_FRONTEND_CHECKS.md)。

DelveForge 推荐在 **Windows + PowerShell** 下运行，面向本地使用，不建议直接部署到公网。

## 1. 环境要求

- **JDK 21**、**Git**
- **Node.js**：`^20.19.0 || >=22.12.0`
- **pnpm 12.9.1**
- **DeepSeek API Key**
- **Docker Desktop（Linux 容器，Engine API 1.48+）**：仅使用 AI 自动修改代码时需要

项目自带 Maven Wrapper，使用 SQLite，无需另外安装 Maven 或数据库服务。

## 2. 一键启动

打开 PowerShell，执行：

```
git clone https://github.com/ayywl288/DelveForge.git
cd DelveForge
.\setup-delveforge.ps1
```

按提示输入 DeepSeek API Key。启动脚本会检查依赖、构建后端、安装前端依赖，并打开前后端服务终端。首次启动可能需要下载依赖和 Docker 镜像。

启动成功后访问 **http://127.0.0.1:5173**（后端默认端口为 `8080`）。停止服务时，在对应终端按 `Ctrl+C`。

**暂时不使用代码自动修改功能？** 可以跳过 Docker，使用：

```
.\setup-delveforge.ps1 -DisableAgent
```

该选项只关闭 Coding Agent。需求对话、仓库分析等 AI 功能仍需要 DeepSeek API Key，启动脚本也仍会要求输入 Key。

## 3. 默认存储位置

以下路径均相对于 **DelveForge 项目根目录**：

| 内容           | 默认路径              |
| -------------- | --------------------- |
| SQLite 数据库  | `data/delveforge.db`  |
| 代码工作副本   | `data/workspaces/`    |
| Agent 运行材料 | `data/agent-runs/`    |
| 应用日志       | `logs/delveforge.log` |

**注意**：启动已有数据库时可能自动执行数据库迁移。升级前先停止前后端服务，再备份数据库、代码工作副本和 Agent 留存材料；正在使用的 SQLite 数据库不能只复制主 `.db` 文件充当完整备份。

## 4. 自定义配置

启动时在 `Customize configuration? [y/N]` 处输入 `y`，可修改：

- 后端端口（默认 `8080`）
- DeepSeek 模型（默认 `deepseek-flash`）和 API 地址（默认 `https://api.deepseek.com`）
- SQLite 数据库文件位置（默认 `data/delveforge.db`）
- 代码工作副本目录（默认 `data/workspaces/`；自定义时需填写绝对路径）
- 日志目录（默认 `logs/`）
- 历史代码工作副本目录（默认空；多个绝对路径用逗号分隔）

留空即使用默认值。修改后端端口时，脚本会自动调整前端代理；前端端口固定为 `5173`。

## 5. 常见问题

- **启动时提示版本不匹配**：检查 `JAVA_HOME`、`java -version`、`node --version` 和 `pnpm --version`。
- **Docker 不可用**：先启动 Docker Desktop 并切换到 Linux 容器；只体验需求探索时可使用 `-DisableAgent`。
- **沙箱工具协议不兼容**：先停止服务并重新运行启动脚本，让它按当前源码检查并准备默认镜像。自定义镜像须从当前 [Agent Dockerfile](../backend/delveforge-infrastructure/src/main/resources/agent/Dockerfile) 构建为新标签，用 `-AgentImage21` / `-AgentImage17` 显式指定后重新启动；脚本仍会核验镜像内容与协议。
- **页面打不开或 API 连接失败**：检查前后端终端是否正常启动，以及端口 `5173`、`8080`（或自定义端口）是否被占用。
- **模型无法调用**：确认 API Key、模型名称和 DeepSeek 账户额度。

需要自行验证源码时，可从仓库根目录执行 `.\mvnw.cmd verify`（后端测试）及 `pnpm --dir frontend test`（前端测试）。

> 当前 HTTP 接口主要用于本地开发，没有面向公网的用户认证和多租户隔离。
