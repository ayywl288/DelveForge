# DelveForge

DelveForge 是在 Windows 上使用的本地项目开发工作台。导入已有项目或创建空项目后，可以请 Agent 解释代码、讨论方向、修改文件、执行测试，或为正在运行的服务打开本机预览。一个项目有一份长期工作副本，多段对话各自保存历史，共享这份副本的实际文件。

## 在本机启动

准备 Docker Desktop（Linux 容器）、JDK 21、Git、项目支持的 Node.js 和 pnpm 12.9.1。在仓库根目录打开 PowerShell。

```powershell
.\setup-delveforge.ps1
```

脚本使用当前进程的 `DEEPSEEK_API_KEY`，也可在提示时输入。首次启动会构建本地镜像、后端和前端依赖。浏览器打开 [本机工作台](http://127.0.0.1:5173)。[安装与配置](docs/LOCAL_SETUP.md)

1. 在「新建或导入项目」中选「从本机导入」，点「选择文件夹…」打开 Windows 目录选择器。确认项目名称后点「创建工作副本」。也可用「手动输入路径」填写绝对路径。要从零开始，选「空项目」并填写名称。
2. 在项目对话中发送任务。可以继续发送纠正，或在需要时停止 Run。不同项目默认可同时运行两项任务。同项目的其他对话会排队，空闲后自动开始。
3. 选择「打开文件夹」用 Windows 编辑器查看、编辑同一份文件。默认路径为 `data\workbench\projects\<项目 ID>`。原始目录保留在导入位置。
4. 要预览网页，可请 Agent 启动项目的实际服务并登记预览，随后点击对话中的「打开本机预览」。服务退出或被替换后需要重新登记。[工作台使用说明](docs/WORKBENCH.md)

## 项目文档

- [Windows 安装与配置](docs/LOCAL_SETUP.md) · [项目、对话、工具与恢复](docs/WORKBENCH.md)
- [项目开发环境与并行调度（ADR-0029）](docs/decisions/0029-project-isolated-runtime.md) · [长对话压缩策略与验证](docs/WORKBENCH_CONTEXT.md)

工作台使用独立的控制数据库和项目工作副本。[产品](docs/PRODUCT.md)、[架构](docs/ARCHITECTURE.md)、[领域模型](docs/DOMAIN_MODEL.md)和[路线图](docs/ROADMAP.md)的开头说明当前工作台，后文保留了既有发布的 Plan、Candidate、Delivery 流程。
