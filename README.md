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

Backend 四个 Module 的依赖方向为
`delveforge-domain ← delveforge-application ← delveforge-infrastructure`，
`delveforge-app` 负责组装 Application 与 Infrastructure。具体规则见
`AGENTS.md` RULE-ARCH-001 与 `docs/ARCHITECTURE.md`。

## Environment

- JDK 21（必需）
- Node.js `^20.19.0 || >=22.12.0` 与 npm（仅在开发 Frontend 时需要）
- 无需预先安装 Maven，仓库自带 Maven Wrapper

## Quick Start

首次构建或 Module 变更后，需要先把各 Module 安装到本地仓库：

```bash
./mvnw install -DskipTests
```

启动后端：

```bash
./mvnw -pl backend/delveforge-app spring-boot:run
```

若已执行过 `./mvnw package`，也可以直接运行打包产物：

```bash
java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar
```

启动前端开发服务器（另开一个终端）：

```bash
cd frontend
npm install
npm run dev
```

后端默认监听 `http://localhost:8080`，前端开发服务器默认 `http://localhost:5173`，
并把 `/api` 代理到后端。Windows 下把 `./mvnw` 换成 `mvnw.cmd`。

## Verification

两条命令链相互独立，都能在全新环境中重复执行，
且不依赖真实 LLM、开发数据库或本地用户数据。

### Backend

```bash
./mvnw verify
```

该命令在仓库根目录执行，覆盖 Backend 的完整验证范围
（其覆盖内容与各 Module 的测试分工见 `docs/ARCHITECTURE.md` §6.4）。
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

前端当前的验证范围与测试工具链取舍见 `frontend/README.md`。

## Configuration

后端配置入口是 `backend/delveforge-app/src/main/resources/application.yml`。
环境相关值都可以用命令行参数或环境变量覆盖：

```bash
# 数据库文件位置（默认 ./data/delveforge.db）
--delveforge.persistence.database-file=/path/to/delveforge.db
DELVEFORGE_PERSISTENCE_DATABASE_FILE=/path/to/delveforge.db

# 临时按包开启日志（默认 INFO）
--logging.level.com.ayywl.delveforge.<package>=DEBUG
```

排查问题时不要整体打开 `com.ayywl.delveforge=DEBUG`。

配置、日志与凭据的完整约定见 `docs/ARCHITECTURE.md` §6.2；
数据库与 Schema 迁移约定见同文 §6.1。

## Documentation

- `docs/PRODUCT.md` — 产品目标、目标用户与 MVP 范围
- `docs/ARCHITECTURE.md` — 系统结构、模块边界、依赖规则、技术约束与横切约定
- `docs/DOMAIN_MODEL.md` — 领域概念、状态模型、Invariant 与 Domain Operations
- `docs/ROADMAP.md` — Milestone、当前开发优先级与 Definition of Done
- `docs/decisions/` — 长期架构决策（ADR）
- `AGENTS.md` — Coding Agent 在本仓库的工作规则
- `frontend/README.md` — Frontend 开发、构建与验证细节
