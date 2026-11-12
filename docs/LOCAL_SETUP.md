# 本地安装与启动

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

启动成功后访问 **http://127.0.0.1:5173**（后端默认端口为 `8080`）。停止服务时，在对应终端按 `Ctrl+C`。更新代码后，先停止旧服务，再重新运行启动脚本。

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

<a id="agent-build-preflight"></a>

Agent 的单次输入预算可用 `delveforge.agent.context-window-tokens`（默认 1,000,000）、`response-tokens`（默认 65,536，包括思考和可见输出）与 `compaction-threshold`（默认 0.90）调整。默认在估计输入达到 841,018 tokens 时压缩，目标为 700,848 tokens。分词器和 CPU 原生库随应用打包；如环境已配置 DJL 的 `RUST_FLAVOR`，请设为 `cpu` 或移除此变量。当前模板适配 `deepseek-flash` 当前的 V4.1-Flash-1017 文本合同。`delveforge.ai.deepseek.max-request-bytes` 独立限制 HTTP 请求大小，默认 8 MiB。

`delveforge.agent.thinking` 接受 `disabled`、`low`、`high`（默认）、`max`，同时控制 HTTP 请求和输入计数。画像提取与 Discovery 对话规划均关闭思考，其他文本请求保留提供者选项。

## 5. 常见问题

- **启动时提示版本不匹配**：检查 `JAVA_HOME`、`java -version`、`node --version` 和 `pnpm --version`。
- **Docker 不可用**：先启动 Docker Desktop 并切换到 Linux 容器；只体验需求探索时可使用 `-DisableAgent`。
- **沙箱工具协议不兼容**：先停止服务并重新运行启动脚本，让它按当前源码检查并准备默认镜像。自定义镜像须从当前 [Agent Dockerfile](../backend/delveforge-infrastructure/src/main/resources/agent/Dockerfile) 构建为新标签，用 `-AgentImage21` / `-AgentImage17` 显式指定后重新启动；脚本仍会核验镜像内容与协议。
- **页面打不开或 API 连接失败**：检查前后端终端是否正常启动，以及端口 `5173`、`8080`（或自定义端口）是否被占用。
- **模型无法调用**：确认 API Key、模型名称和 DeepSeek 账户额度。

需要自行验证源码时，可从仓库根目录执行 `.\mvnw.cmd verify`（后端测试）及 `pnpm --dir frontend test`（前端测试）。

> 当前 HTTP 接口主要用于本地开发，没有面向公网的用户认证和多租户隔离。

## 6. 仓库输入、离线执行与失败材料

选中仓库的源码、配置和环境示例会提供给模型及隔离命令，也可能进入日志。请勿包含不愿暴露的有效凭据；尽力脱敏不能保证任意命令的保密性。
`.git/.hg/.svn/.ssh/.aws/.gnupg/.m2` 控制目录仍不支持，路径、链接、大小和二进制编辑限制不变；不会把宿主凭据或正式副本挂载到 Agent。

当前工具镜像协议为 **v10**，旧镜像必须通过上述启动脚本重建。网络报告为 OFFLINE 时没有下载能力，但可继续离线工作；
CONTROLLED 仅表示固定工件代理已启动，不保证依赖下载成功。实际错误见命令结果。

捕获失败的命令保留真实退出码和失败原因，不应直接重放。失败 Run 的源卷和 Run 目录可能保留用于人工诊断；它们不是可交付 Candidate。
不要在写入者停止和持久状态确认前手工删除这些材料。重启协调只停止/核对，不恢复 Agent 对话。
