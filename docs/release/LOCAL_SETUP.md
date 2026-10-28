# 本地环境、构建与启动

本轮验证平台：Windows / PowerShell 7、JDK 21.0.12.1、Node 22.16.0、pnpm 12.9.1。
支持要求以仓库配置为准：JDK 21、Git CLI、Node `^20.19.0 || >=22.12.0`、pnpm 12.9.1。
Java 17 是目标工程的受控工具镜像选项，不是运行 DelveForge 的 JDK。

## 获取正确代码并构建

候选当前仅在本地 `codex/mvp-freeze-release`，公共 main 尚未集成这批实现。
从持有该 ref 的仓库取得候选；用 `git rev-parse HEAD` 记录精确版本，不假定公共 clone 已包含它。
所有命令从仓库根目录执行，不需要单独安装数据库服务或宿主 Python。

```powershell
java -version
git --version
node --version
pnpm --version
.\mvnw.cmd verify
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend test
pnpm --dir frontend build
```

首次构建可能联网下载公开依赖。依赖齐备后可用 `.\mvnw.cmd -o verify`。
只需要重新打包时可用 `.\mvnw.cmd -o -DskipTests package`；它跳过测试，不能作为新测试证据。
后端产物在 `backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar`。
前端 `build` 包含 vue-tsc 和 Vite；没有独立 lint 脚本。

## 无密钥、无 Docker 的独立启动检查

以下只启动本地 UI/API 和新数据库，禁用 Agent，不触发模型。
可验证安装与技术链路，不能体验真实需求对话、分析或编码。
端口 28930/28931 需空闲；不要结束不属于本次启动的进程。

```powershell
$demoRoot = Join-Path (Get-Location) ('data/demo-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $demoRoot -ErrorAction Stop | Out-Null
$env:DEEPSEEK_API_KEY = $null
$env:DELVEFORGE_AGENT_ENABLED = 'false'
$env:DELVEFORGE_PERSISTENCE_DATABASE_FILE = Join-Path $demoRoot 'application.sqlite'
$env:DELVEFORGE_WORKSPACE_ROOT = Join-Path $demoRoot 'workspaces'
$env:DELVEFORGE_WORKSPACE_EXISTING_ROOTS = ''
$env:DELVEFORGE_AGENT_ROOT = Join-Path $demoRoot 'agent-runs'
$env:DELVEFORGE_LOG_DIR = Join-Path $demoRoot 'logs'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '28930'
java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar
```

另开终端，从同一仓库根目录执行：

```powershell
$env:BACKEND_DEV_URL = 'http://127.0.0.1:28930'
pnpm --dir frontend dev --host 127.0.0.1 --port 28931 --strictPort
```

打开 `http://127.0.0.1:28931/`。技术核对：

```powershell
Invoke-RestMethod http://127.0.0.1:28930/api/system/connectivity
Invoke-RestMethod http://127.0.0.1:28931/api/system/connectivity
Invoke-WebRequest http://127.0.0.1:28931/api/projects
```

connectivity 的 UP 仅表示本地 HTTP 可应答，不表示 Provider、Docker 或交付能力就绪。
新库的项目 API 为 `[]`。停止各终端用 Ctrl+C；保留新库供检查，不清理旧库。
这些环境变量只对对应终端及子进程生效，后续如要启用 Agent，使用新的正常启动终端。

## 正常交互与 Coding Agent

```powershell
.\setup-delveforge.ps1
```

脚本会要求 JDK/Node/pnpm 与 key，检查 Docker，按当前工具源码准备并核验可信镜像，再启动前后端。
缺 Docker 而只使用探索/规划时可显式 `-DisableAgent`；脚本仍交互获取 key，探索阶段不是免费离线功能。
正常默认端口为后端 8080 / 前端 5173；自定义端口时同步 Vite 的 `BACKEND_DEV_URL`。
`pnpm preview` 不提供开发 API 代理，不能替代上述启动。

Agent 需 Linux Docker Engine API 1.48+、受控 JDK 17/21 镜像与公开 Central 依赖。
只支持已声明的文件、构建与原生交付边界；首次镜像/依赖准备需要网络。
key 只留后端进程环境或安全配置，不进前端、启动参数、源码与日志。每次 Agent Run 仍需产品内费用确认。
模型默认是配置中的 `deepseek-flash`；账号是否可用需自行确认，可配置 model/base-url，本次没有新 Provider 验证。

## 数据升级与回退

默认库为启动工作目录的 `data/delveforge.db`，当前 Flyway 到 V26。应用启动即迁移，先备份再启动。
正常停止后使用 SQLite backup API 做一致备份并检查 integrity；有 WAL 时不能只复制主文件。
成组备份全部 WorkingCopy roots（包括 existing-roots 与隐藏 Git）、Agent 留存材料、旧配置和应用包。
测试迁移必须隔离数据库及所有副本路径，不能让克隆库继续指向原副本。
不要降级 migration 或只恢复数据库/源码其中一边。详细合同见 [Agent 运行说明](../AUTONOMOUS_AGENT.md#local-startup-and-upgrade)。

## 常见问题

| 现象 | 检查与处理 |
| --- | --- |
| Java 版本错误 | 设置 JAVA_HOME 和 PATH 为含 javac 的 JDK 21；用 java/javac -version 核对 |
| pnpm 或 Node 不匹配 | 按 package.json 的 engines/packageManager 安装；锁文件保持不变 |
| 离线构建缺依赖 | 首次允许正常公开依赖下载，补齐后再离线；不以零测试构建代替功能验证 |
| Docker 不可达或协议不匹配 | 启动 Linux Engine，使用当前源码启动器核验；不强行启用 Agent 或放宽隔离 |
| 页面能开、API 不通 | 核对后端端口、BACKEND_DEV_URL 和 connectivity；重新启动 Vite 读取环境 |
| DELIVERY_BLOCKED / RECOVERY_REQUIRED | 保留材料，按已知状态显式核对；不 reset/clean 或清占用标记 |
| Taskarium 登录/注册 403 | 是已交付目标应用的 CSRF 回归，不是 DelveForge 安装失败；冻结期不修补 |

本地开发接口无用户登录认证和多租户隔离，保持 loopback；本说明不是生产部署指南。
