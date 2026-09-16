DelveForge (Formerly: Hobby2Project) helps developers discover what to build and forge existing software assets into products they actually want.

DelveForge：Delve into the user and existing assets → Forge a product.   ||   Delve for possibilities, forge them into products. || Delve what’s worth building. Forge it into something truly yours.

## Repository Structure

```text
DelveForge/
├── backend/
│   ├── delveforge-domain/          领域模型、领域规则与 Invariant
│   ├── delveforge-application/     Use Case、编排与 AI / Workspace / Persistence Port
│   ├── delveforge-infrastructure/  外部能力 Adapter（AI、Persistence、Git、Shell 等）
│   └── delveforge-app/             Spring Boot Bootstrap、REST API 与依赖装配
├── frontend/                       Vue 3 + TypeScript + Vite（Web UI）
├── docs/                           产品、架构、领域与 Roadmap 文档
├── pom.xml                         Maven 聚合根
└── mvnw / mvnw.cmd                 Maven Wrapper
```

Module 依赖方向必须保持为：

```text
delveforge-domain  →  （不依赖其他 DelveForge Module）
        ↑
delveforge-application
        ↑
delveforge-infrastructure

delveforge-app  →  delveforge-application + delveforge-infrastructure
```

禁止反向依赖。该约束已通过 `maven-enforcer-plugin` 在构建期校验，违反时 `verify` 会直接失败。

## Environment

- JDK 21（必须）
- 无需预先安装 Maven，仓库自带 Maven Wrapper

## Verification

项目验证由两条独立的命令链组成。两者都能在全新环境中重复执行，
不依赖真实 LLM、开发数据库或任何本地用户数据。

### Backend

```bash
./mvnw verify
```

Windows 下使用 `mvnw.cmd verify`。该命令在仓库根目录执行，覆盖当前 Backend 的完整验证范围：

```text
编译四个 Module
maven-enforcer 校验模块依赖方向（RULE-ARCH-001），违反即失败
执行全部自动化测试
打包 delveforge-app 可执行 Jar
```

开发期做定向迭代时，可以只跑受影响的部分：

```bash
# 某个 Module 的测试（-am 一并构建它依赖的 Module）
./mvnw -pl backend/delveforge-application -am test

# 某个测试类
./mvnw -pl backend/delveforge-app -am test \
  -Dtest=ApiExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false
```

### Frontend

```bash
cd frontend
npm ci          # 按 package-lock.json 干净安装
npm run build   # vue-tsc 类型检查 + Vite 生产构建
```

Frontend 当前没有 lint 与 test script，也没有引入 ESLint / Vitest：
目前只有 `App.vue` 与一个 API 调用模块，没有值得单独测试的业务逻辑；
类型错误已由 `vue-tsc` 与 tsconfig 的 `noUnusedLocals` / `noUnusedParameters` 覆盖。
再评估的时机见 `frontend/README.md`。

### 测试组织

| Module | 测试内容 | 依赖外部技术 |
| --- | --- | --- |
| `delveforge-domain` | User Profile 等领域规则与状态语义，纯 JUnit | 否，纯 JUnit |
| `delveforge-application` | Port 契约与取值约束，在 Port 边界使用 fake | 否，纯 JUnit |
| `delveforge-infrastructure` | 真实 SQLite + Flyway + MyBatis-Plus 集成 | 是，`target/` 下的临时数据库 |
| `delveforge-app` | 上下文装配、HTTP 端点、错误映射、配置绑定 | 仅 Web 层，数据库同为临时文件 |

```text
Application 测试   在 Port 边界使用 fake，不引入 Spring，也不依赖 Infrastructure
Infrastructure 测试 必须使用真实技术，不能只用 Mock 代替（AGENTS.md 10.3）
数据库测试          一律使用 target/test-databases/<uuid>/ 下的临时 SQLite 文件，
                    不接触开发者本地数据库，也不写入仓库工作目录
LLM                自动化测试不调用真实模型，相关能力通过 Port 边界替身验证
```

## Run

首次构建或 Module 变更后，需要先把各 Module 安装到本地仓库：

```bash
./mvnw install -DskipTests
```

然后启动应用：

```bash
./mvnw -pl backend/delveforge-app spring-boot:run
```

若已执行过 `./mvnw package`，也可以直接运行打包产物：

```bash
java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar
```

## Database

MVP 使用 SQLite，数据库文件位置由配置属性 `delveforge.persistence.database-file` 决定，
默认值为 `./data/delveforge.db`（相对路径相对于进程工作目录，缺失的父目录会在启动时自动创建）。

可通过命令行参数或环境变量覆盖：

```bash
java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar \
  --delveforge.persistence.database-file=/path/to/delveforge.db
```

```bash
export DELVEFORGE_PERSISTENCE_DATABASE_FILE=/path/to/delveforge.db
```

Schema 由 Flyway 在启动时自动迁移，迁移文件位于
`backend/delveforge-infrastructure/src/main/resources/db/migration`，
命名格式为 `V{版本}__{描述}.sql`。已发布的迁移文件不可修改，只能追加新版本。

SQLite、MyBatis-Plus 与 Flyway 只允许出现在 `delveforge-infrastructure`。
Domain 与 Application 模块不得出现这些技术类型的依赖。

## Logging

默认日志级别为 `INFO`。排查问题时可临时按包开启，例如：

```bash
java -jar ... --logging.level.com.ayywl.delveforge.<package>=DEBUG
```

不要整体打开 `com.ayywl.delveforge=DEBUG`：Mapper 接口位于
`com.ayywl.delveforge.infrastructure.persistence`，该包打开 DEBUG 会让 MyBatis
打印 SQL 与绑定参数值，其中可能包含用户数据。排查结束后请恢复。

日志格式约定为 `operation=<操作> path=<资源标识> result=<结果> exception=<异常类型链 + 堆栈位置>`。
日志记录的是异常类型与堆栈位置，**不记录异常 message 与原因链**——
这些文本可能嵌入请求内容、用户数据或第三方 SDK 原始返回中的凭据，
且无法在记录前可靠判定。

日志中禁止出现 API Key / Token / Credential、完整源码、完整 Prompt 或模型响应、成批用户数据，
该规则**不区分日志级别**。

`org.springframework.web`、`org.apache.tomcat`、`org.apache.coyote` 在 `application.yml`
中被固定为 `INFO`：它们在 DEBUG 下会输出未经筛选的原始异常文本或请求头，
且该输出发生在应用代码之外，无法通过 `@RestControllerAdvice` 拦截。
这些固定是最低保障，不是完整清单。

配置 / 错误处理 / 日志的完整约定见 `docs/ARCHITECTURE.md` §6.2。

## Frontend

`frontend/` 是 DelveForge 的 Web UI，使用 Vue 3 + TypeScript + Vite，
包管理器为 npm（`package-lock.json` 已提交）。

```bash
cd frontend
npm install
npm run dev
```

开发服务器默认 `http://localhost:5173`，会把 `/api` 代理到本地 Backend
（默认 `http://localhost:8080`，由 `frontend/.env.development` 的 `BACKEND_DEV_URL` 决定）。

因此前端代码始终使用相对路径 `/api/...`，不持有 Backend 地址，Backend 也无需开放 CORS。

Frontend 只负责用户交互与展示；领域规则、Repository 操作与 Git / Filesystem / Shell
等本地能力全部在后端。完整验证命令与测试约定见上方 [Verification](#verification)，
更多细节见 `frontend/README.md`。

更详细的架构边界与开发规范见 `AGENTS.md` 与 `docs/ARCHITECTURE.md`。