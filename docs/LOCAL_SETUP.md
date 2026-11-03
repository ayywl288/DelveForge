# 本地安装与启动

Agent 当前工具协议为 **v8**，包含固定 Node 22.16.0、npm 10.9.2、pnpm 12.9.1 和兼容 generation 6 的 pnpm 8.15.9。
升级后重新运行启动器构建和核验当前镜像；旧 v7 及更早镜像会在传入源码前被拒绝。
v8 增加原生安装兼容和模型前环境预检；项目脚本及 Node/Vitest 报告、绑定摘要的资源能力与执行投影继续保留。
正式 Git 保留完整原字节，未物化的敏感资源不会发送给项目进程。
工程选择、就绪条件与脚本执行隔离见下方 [Coding Agent 操作说明](#agent-build-preflight)。

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


<a id="agent-build-preflight"></a>

## 6. Coding Agent 原生构建与预检

设计理由见 [ADR-0021](decisions/0021-sandbox-enforced-project-scripts-and-test-evidence.md) 与
[ADR-0022](decisions/0022-native-build-environment-preflight.md)。当前协议 **v8**、证据 **v4**；
从当前 Dockerfile 用新标签构建 JDK 17/21 镜像，启动器核对脚本和固定工具版本，不覆盖在用旧标签。

### 正常流程与工程选择

确认 Step → PREPARING 中隔离准备和实际离线基线探针 → 模型循环 → 当前源码检查 → Candidate/Delivery。
预检不新增生命周期、不推进可信 revision，也不替代最终检查。预检阻塞的 Run 模型请求、modelCalls、tokens 均为 0。
每次启动仍需原有费用确认；Plan 的 problem、targetProduct、differentiation 各净化并限制到 3000 字符，
仅解释当前 Step，不携带完整画像/聊天，也不扩大修改授权。

| 预检结果 | 含义 | 进入模型循环 |
| --- | --- | --- |
| READY | 实际检查路径已执行，必要能力有观察证据 | 是 |
| READY_BASELINE_FAILURE | 实际编译/测试后发现明确源码或测试失败 | 是，必须修复并重验 |
| ENVIRONMENT_BLOCKED | 工具、依赖、外部前提或必要证明不足 | 否 |
| SECURITY_BLOCKED | 可信源码或隔离边界不能成立 | 否 |
| SELECTION_REQUIRED | 多个独立工程，检查入口尚未明确 | 否 |

唯一入口自动选择；普通 Maven + Vue/Vite 组合可一起检查。多入口在运行配置或正常启动请求中传入
`checkDirectories`，例如 `{"paidUsageAcknowledged":true,"targetJdk":21,"checkDirectories":["server","apps/web"]}`。
最多 8 个目录、每个 200 字符，必须来自实际发现的工程；仓库根为 `.`。选择不增加源码修改权限。
事件记录候选目录、observedPhase、unprovenCapabilities 和有界诊断；编译失败不能证明测试 runner 已运行。

### 原生依赖准备

- Maven Reactor/profile 由实际 Maven 求值，使用固定 JDK 17/21，不自动下载其他 JDK。
- npm 10.9.2 对 v2/v3 package-lock 或 shrinkwrap 使用原生 ci；无锁在私有准备副本 install。
- pnpm 12.9.1 处理 generation 9，pnpm 8.15.9 处理 generation 6，普通 workspace 关联交给原生 pnpm。
  已核验为固定 pnpm 的独立工具链文档仅在准备副本分离；项目锁、依赖关系保留，自动工具下载关闭。
- 无锁解析按 Run 冻结，记录 resolutionSha256 和 RUN_FROZEN_RESOLUTION，不把生成锁写回正式源码。
- npmjs、npmmirror、npm.taobao、yarnpkg 的明确校验公共地址可映射；下载始终通过固定 HTTPS 上游代理。
  安装期脚本/hooks 关闭，受限公开 `.npmrc` 与 peer/hoist 设置经校验才传入；不提供凭据、私有 registry、
  git/任意 URL 依赖、未知配置/catalog/hooks 或自动运行时下载。包摘要仍由真实安装器校验。
- manifests、锁、workspace、受控配置的摘要改变会使准备失效；同一 Run 相同输入可只读复用私有准备卷。
  项目不能修改依赖准备卷；共享缓存仅保存公开工件，不能替代 integrity 核验。
- Maven 项目代码及 go-offline 均离线执行。联网准备只使用固定空 POM，通过 dependency:get 获取实际诊断中
  可证明的受限工件身份，最多 8 轮、32 个身份；耗尽或身份不足就阻塞，不回退到联网生命周期。

安全 Git 准入不以 Maven 根数/profile、锁文件代数/缺失或脚本声明整体拒绝；这不保证构建环境就绪。
固定 Git 准入预算仍适用，Maven 描述及 workspace 有界元数据也可能阻塞执行。
安装期 native build、自定义运行时、外部服务以及其他未支持能力可能不可用；未知非零退出保守阻塞。

### 项目脚本、报告与隔离

`run_frontend_checks` 的 directory/goal 支持实际 Vue 3 + Vite 工程的 npm/pnpm 脚本；
TypeScript、vue-tsc、Vitest 都不是整体准入前提，纯前端不制造 Maven POM。
goal 支持 typecheck（typecheck/type-check）、build（build/build-only）、test（test/test:unit），优先前者。
没有脚本为 CHECK_NOT_AVAILABLE；预检中未声明测试为 NOT_DECLARED，不制造测试结果。

脚本作为不可信代码在一次性无网络容器运行；不以字符串白名单判定安全，不提供任意宿主 shell。
正式 WorkingCopy 不挂载，不接收 node_modules、构建或测试产物。检查副本使用非 root、只读容器根、
cap-drop、no-new-privileges、CPU/内存/PID、输出及超时限制；停止并回收所有子进程后核对源码和报告。
安装准备与执行分离；执行不获得主机环境、密钥或 Docker 套接字。取消/超时后停止容器写入者并清理私有执行卷。

Node --test 自动采集 JUnit，Vitest CLI 单次执行采集 JSON；NODE_OPTIONS 包装须保留报告 preload。
最多 32 份报告、每份 2 MiB；独立核对 runner 退出和计数，wrapper 零退出不能掩盖 runner 失败。
其他框架/程序化 API/覆盖 NODE_OPTIONS 的包装可能没有报告，成功退出为 TEST_EXECUTION_UNVERIFIED，不能声称测试通过。
固定 Node 22.16.0 的 `node --test tests/` 会 MODULE_NOT_FOUND，应依据真实诊断选择实际文件/glob，工具不代修源码。

检查前后核对完整 snapshot；一次性副本可排除选中工程的 node_modules、dist、coverage、.vite、.df-check、
tsbuildinfo 等已分类生成物，生成目录根不能是符号链接。其他源码变化触发 CHECK_MUTATED_SOURCE。
源码工具查询依赖材料返回 CHECK_MATERIAL_UNAVAILABLE 和检查入口说明，不把准备卷暴露成源码。

### 如何阅读结果

证据 v4 的 baselineFailures 与正常 checks 分开；基线成功不计最终通过/测试数，失败必须匹配重验。
v1–v3 按历史规则读取，不迁移或重写。每项检查绑定工程、goal、runner、配置摘要、snapshot 和 stale 状态；
UI 区分 NOT_STARTED / EXECUTED / UNKNOWN，只对实际执行范围作结论。

- typecheck/build 成功仅为 BUILD_PASSED_NOT_TESTED。
- 新报告实际执行数大于 0 且成功才为 TESTS_PASSED；零测试、缺报告、失败分别记录。
- 前端失败必须以同工程、目标、匹配 runner 的当前源码重验解决；Maven 成功、build 成功或删除脚本不能清除 test 失败。
- FULL/PROJECTED 和 executionBaselineTree 描述执行树 E，可信 baseline R 保持完整 Git；主机保留的源码/测试/
  构建描述不能通过删除后剩余检查生成 PASS。资源身份、独立重放、Candidate 和 Delivery 守卫保持。
- 配置声明及 describe_checks 仅说明能力，不是安装或通过证明；无检查可为 NOT_TESTED，不能伪造 PASS。

没有自动浏览器/业务验收 Agent，技术通过不等于用户工作流可用。当前真实验证及限制见
[原生预检报告](validation/native-build-preflight-20261103.md)、
[脚本证据与真实产品验收](validation/autonomous-homeops-continuation-20261103.md)；
[初次前端验证](validation/frontend-checks-initial-20261102.md)、
[通用兼容性审查](validation/general-compatibility-20261102.md) 与
[内容能力验证](validation/safe-admission-boundary-20261102.md) 保留当时版本和失败，不能当作当前全量重跑。
