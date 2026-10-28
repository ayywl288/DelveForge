# DelveForge

**Personalized Project Discovery & Evolution Agent**

> 🔨 **Evolution Before Rewrite.**
>
> 从真实需求出发，发现值得做的项目，让已有代码成为新想法的起点。

![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F)
![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D)

## 🎯 About DelveForge

完成一个课程项目或教程项目后，下一个项目应该做什么？

热门项目清单可以提供题目，却很难回答：**这个项目为什么适合我？能否满足真实需求？我已经写过的代码有没有复用价值？**

DelveForge 是一个面向开发者的个性化项目探索与演化 Agent。

它通过 AI 对话理解用户的兴趣、痛点、技术能力和项目目标，分析已有 Git 仓库的功能与可复用资产，再提出个性化产品方向和演化计划。

在此基础上，DelveForge 可以在用户逐步授权后，通过 Coding Agent 在 Docker 隔离环境中修改独立 WorkingCopy，执行构建与测试，将修改冻结为 Candidate，再经过验证和交付流程推进可信版本。

## ✨ What Can It Do?

DelveForge 提供项目工作台，以及从需求探索到代码验证的五个业务阶段。

| 功能 | 说明 |
| --- | --- |
| 🗂️ **Project Workspace** | 管理不同项目、查看历史记录和已验证步骤，支持继续项目与重新规划 |
| 💬 **User Discovery** | 自然语言多轮探索、画像提取与充分性反馈；生成六维用户画像，由用户审阅、纠正并确认 |
| 🔍 **Repository Understanding** | 只读分析本地 Git 仓库，识别模块、技术栈、已有能力、可复用资产、限制和风险 |
| 💡 **Product Direction** | 结合用户需求与已有代码，生成个性化产品方向，展示依据与风险，由用户选择 |
| 📋 **Evolution Planning** | 为选定方向生成演化计划，拆分步骤、修改范围和验收标准 |
| 🧪 **Verified Evolution** | 用户确认 Step 后，Coding Agent 在 Docker 中编辑独立副本、运行检查、冻结 Candidate 并交付经过验证的修改 |

### 🧭 Workflow

```text
Explore Your Needs
        ↓
Understand Existing Repositories
        ↓
Discover Product Directions
        ↓
Plan Incremental Evolution
        ↓
Review & Confirm Steps
        ↓
Execute in Docker Sandbox
        ↓
Verify & Deliver Candidate Changes
```

DelveForge 不会自动替用户决定产品方向，也不会在选择方向后直接修改代码。

**AI Proposes, Domain Decides.**

AI 提出建议，系统检查结构、依据、授权和状态。代码修改只有满足相应验证要求后，才能成为可信的 Verified Revision。


## 🚀 Quick Start

目前推荐在 **Windows 本地环境**中运行。

### 1. Requirements

- **JDK 21**
- **Git CLI**
- **Node.js**：`^20.19.0` 或 `>=22.12.0`
- **pnpm 12.9.1**
- **DeepSeek API Key**（调用 AI 时需要）
- **Docker Desktop / Linux Docker Engine**（使用 Coding Agent 时需要）

### 2. One-Command Startup

在 PowerShell 中获取仓库，并从仓库根目录启动：

```
git clone https://github.com/ayywl288/DelveForge.git
cd DelveForge
.\setup-delveforge.ps1
```

根据提示配置 **DeepSeek API Key** 和所需选项即可。

### ⚙️ Custom Configuration

启动时选择 `Customize configuration? [y/N]` 中的 `y`，可以配置以下 **7 项**：

- 后端端口（默认 `8080`）
- DeepSeek Model（默认 `deepseek-flash`）
- DeepSeek Base URL（默认 `https://api.deepseek.com`）
- SQLite 数据库文件路径（默认 `data/delveforge.db`）
- WorkingCopy 根目录（默认 `data/workspaces/`，自定义时须使用绝对路径）
- 日志目录（默认 `logs/`）
- 历史 WorkingCopy 根目录（默认空；自定义时以逗号分隔多个绝对路径）

**以上可选输入留空即使用默认值。** 修改后端端口时，启动脚本会同步配置前端代理；前端端口固定为 `5173`。

调用真实模型会产生 Token 费用。Coding Agent 执行前需要明确授权；启动应用不会自动开始代码演化。

### 💡 How to Use

启动 DelveForge 后：

1. 在 **Project Workspace** 中新建项目。
2. 通过 **User Discovery** 探索需求，审阅并确认用户画像。
3. 在 **Repository Understanding** 中登记本地 Git 仓库，填写真实的读取权限、许可证信息与使用授权。
4. 运行仓库分析，查看已有功能、结构和可复用能力。
5. 在 **Product Direction** 中生成并选择感兴趣的方向。
6. 通过 **Evolution Planning** 生成并审阅演化计划。
7. 如果愿意继续实验，可以准备 WorkingCopy、确认 Step，并尝试执行代码演化。

**建议首次从 GitHub 手动克隆一个体量较小的 Java/Maven 项目，使用其本地绝对路径进行体验。**

## ⚠️ Current Limitations

- **Java / Maven Evolution Only：**当前完整的代码演化与验证流程仅支持 Java + Maven 项目。系统主要通过 Maven 执行构建与测试，因此其他语言或使用 Gradle、npm 等构建工具的仓库，即使能够被读取和分析，目前也无法完成完整的自动演化流程。

- **No Guaranteed Success or Resume：**Coding Agent 的代码修改与验证不保证每次成功。执行失败后，可在系统允许的状态和授权条件下重新发起执行或重试，但不支持从中断位置断点续跑；失败或状态不确定时，需先核对执行结果。

- **anual Local Repository Setup：**当前不支持自动搜索 GitHub 仓库或自动克隆。用户需要自行从 GitHub 等来源将仓库克隆到本地，再在 DelveForge 中填写该 Git 仓库的绝对路径，完成读取权限与使用授权后才能分析和继续后续流程。

### 📚 Documentation

- [Product Design](docs/PRODUCT.md) · [Architecture](docs/ARCHITECTURE.md) · [Domain Model](docs/DOMAIN_MODEL.md)
- [Roadmap](docs/ROADMAP.md) · [Architecture Decisions](docs/decisions/)
- [Local Setup](docs/release/LOCAL_SETUP.md) · [Demo Catalog](docs/demos/README.md)
- [Frontend Guide](frontend/README.md) · [Validation Records](docs/validation/)
- [MVP Release](docs/release/README.md) · [Architecture Overview](docs/release/ARCHITECTURE_OVERVIEW.md)

---

**🔨 DelveForge — Evolution Before Rewrite.**