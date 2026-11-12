# DelveForge

**Personalized Project Discovery & Evolution Agent**

> 🔨 **Evolution Before Rewrite.**
>
> 从真实需求出发，发现值得做的项目，让已有代码成为新想法的起点。

![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F)
![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D)

## 🎯 About DelveForge

做完课程项目或教程项目后，不知道下一步做什么？

**DelveForge 是一个本地 Coding Agent 平台，帮助开发者结合需求和已有代码，找到适合继续开发的项目方向。** 它通过 AI 对话了解需求，分析指定的本地 Git 仓库并推荐改造方向。你逐步授权后，Agent 在隔离工作区浏览和编辑代码、执行仓库原生开发与测试命令，完成后供你查看变更和验证事实。


## 🚀 Quick Start

推荐使用 **Windows + PowerShell**。需要 JDK 21、Git、Node.js（`^20.19.0 || >=22.12.0`）、pnpm 12.9.1 和 DeepSeek API Key；使用自动代码修改功能还需要 Docker 环境（Linux 容器）。

在 PowerShell 中获取仓库，并从仓库根目录启动：

```
git clone https://github.com/ayywl288/DelveForge.git
cd DelveForge
.\setup-delveforge.ps1
```

根据提示配置 **DeepSeek API Key** 和所需选项即可。

启动后访问 `http://127.0.0.1:5173`，后端默认端口为 `8080`。项目自带 Maven Wrapper，使用 SQLite，无需另装 Maven 或数据库服务。

默认数据统一存放在项目根目录的 `data/` 文件夹下。

启动时选择 `Customize configuration? [y/N]` 中的 `y`，可以修改端口、模型、API 地址及上述存储路径等配置。留空使用默认值；详细配置参见 [Local Setup](docs/LOCAL_SETUP.md)。

### 💡 How to Use

1. 与 AI 对话，描述你的兴趣、技术基础和目标，确认整理出的需求。
2. 选择一个**已克隆到本地的 Git 仓库**，输入绝对路径，让 DelveForge 分析代码并推荐开发方向。
3. 选择方向、查看计划，逐步授权 AI 修改代码和运行测试，检查交付结果；全部步骤交付后可查看独立生成的 Quick Start。

**建议首次使用体量较小、工具链与执行环境匹配的仓库，并提供其本地绝对路径。** Agent 通过通用仓库探索、文件编辑和 Shell 执行工作，不以 Java/Maven 专用适配器作为入口。

## ⚠️ Current Limitations

- **工具链取决于执行环境**：默认受控镜像提供 JDK 17/21、Maven、Node.js/npm/pnpm 和 Python 3 基础环境。其他语言或构建系统需要兼容镜像中已有所需工具；不承诺任意工具链均可运行，也不允许 Agent 随意安装系统工具。
- **依赖与网络受限**：依赖下载只能使用允许的公共工件来源，私有依赖和外部服务可能不可用。命令成功或测试通过不等于业务验收通过。
- **不保证每次演化成功**：失败后可按状态重新执行或重试，但不支持从中断处继续；结果不确定时需先核对状态。
- **不支持自动搜索或克隆 GitHub 仓库**：需要自行克隆到本地，并提供仓库绝对路径。

## 📚 Documentation

[本地安装与配置](docs/LOCAL_SETUP.md) · [系统架构](docs/ARCHITECTURE.md) · [产品设计](docs/PRODUCT.md) · [领域模型](docs/DOMAIN_MODEL.md)

开发日志、截图和一次性验收报告写入已忽略的 `target/`。历史验收材料可通过 `git log -- docs/validation` 查看。

---

**🔨 DelveForge — Evolution Before Rewrite.**
