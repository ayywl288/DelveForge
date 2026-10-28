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

**DelveForge 帮助开发者结合自己的兴趣、目标和已有代码，找到适合继续开发的项目方向。** 它通过 AI 对话了解你的需求，分析你指定的本地 Git 仓库，并推荐基于现有代码的改造方向。确定方向后，你可以让 AI 在隔离环境中尝试修改代码、运行测试，再由你查看结果。


## 🚀 Quick Start

推荐使用 **Windows + PowerShell**。需要 JDK 21、Git、Node.js（`^20.19.0 || >=22.12.0`）、pnpm 12.9.1 和 DeepSeek API Key；使用自动代码修改功能还需要 Docker Desktop（Linux 容器）。

在 PowerShell 中获取仓库，并从仓库根目录启动：

```
git clone https://github.com/ayywl288/DelveForge.git
cd DelveForge
.\setup-delveforge.ps1
```

根据提示配置 **DeepSeek API Key** 和所需选项即可。

默认数据统一存放在项目根目录的 `data/` 文件夹下。

启动时选择 `Customize configuration? [y/N]` 中的 `y`，可以修改端口、模型、API 地址及上述存储路径等配置。留空使用默认值；详细配置参见 [Local Setup](docs/LOCAL_SETUP.md)。

### 💡 How to Use

1. 与 AI 对话，描述你的兴趣、技术基础和目标，确认整理出的需求。
2. 选择一个**已克隆到本地的 Git 仓库**，输入绝对路径，让 DelveForge 分析代码并推荐开发方向。
3. 选择方向、查看计划，逐步授权 AI 修改代码和运行测试，检查最终结果。

**建议首次从 GitHub 手动克隆一个体量较小的 Java/Maven 项目，使用其本地绝对路径进行体验。**

## ⚠️ Current Limitations

- **仅支持 Java + Maven 演化**：其他语言或构建工具的仓库可能可以分析，但目前无法完成自动修改与测试的完整流程。
- **不保证每次演化成功**：失败后可按状态重新执行或重试，但不支持从中断处继续；结果不确定时需先核对状态。
- **不支持自动搜索或克隆 GitHub 仓库**：需要自行克隆到本地，并提供仓库绝对路径。

## 📚 Documentation

[本地安装与配置](docs/LOCAL_SETUP.md) · [系统架构](docs/ARCHITECTURE.md) · [产品设计](docs/PRODUCT.md) · [领域模型](docs/DOMAIN_MODEL.md)

---

**🔨 DelveForge — Evolution Before Rewrite.**