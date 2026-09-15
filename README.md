EvoForge (Formerly: Hobby2Project) helps developers discover what to build and forge existing software assets into products they actually want.

DelveForge：Delve into the user and existing assets → Forge a product.   ||   Delve for possibilities, forge them into products. || Delve what’s worth building. Forge it into something truly yours.

## Repository Structure

```text
DelveForge/
├── backend/
│   ├── delveforge-domain/          领域模型、领域规则与 Invariant
│   ├── delveforge-application/     Use Case、编排与 AI / Workspace / Persistence Port
│   ├── delveforge-infrastructure/  外部能力 Adapter（AI、Persistence、Git、Shell 等）
│   └── delveforge-app/             Spring Boot Bootstrap、REST API 与依赖装配
├── docs/                           产品、架构、领域与 Roadmap 文档
└── pom.xml                         Maven 聚合根
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

## Build & Test

```bash
./mvnw verify
```

Windows 下使用：

```bat
mvnw.cmd verify
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

更详细的架构边界与开发规范见 `AGENTS.md` 与 `docs/ARCHITECTURE.md`。