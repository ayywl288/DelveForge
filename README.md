# DelveForge

DelveForge 是一款运行在 Windows 本机的 Coding Agent 工作台。导入已有项目或从空项目开始，Agent 可以在工作副本中修改代码、运行命令和测试，你也可以在本机打开实际运行的应用来验收。

[![DelveForge 14 秒演示：Agent 修改代码、完成浏览器验证，随后在本机预览中录入一笔支出](https://ayywl.top/media/delveforge/readme-demo-v2.gif)](https://ayywl.top/delveforge/)

[产品主页 · 看完整演示](https://ayywl.top/delveforge/) · [快速开始](#在本机启动) · [架构设计](docs/ARCHITECTURE.md)

## 在本机启动

准备 Docker Desktop（Linux 容器）、JDK 21、Git、Node.js（`^20.19.0 || >=22.12.0`）和 pnpm 12.9.1。在 Windows PowerShell 中执行：

```powershell
git clone https://github.com/ayywl288/DelveForge.git
cd DelveForge
.\setup-delveforge.ps1
```

首次启动会构建本地镜像、后端和前端。打开 [本机工作台](http://127.0.0.1:5173)，在对话输入框下方的模型菜单中配置提供方和 API Key，再运行 Agent。安装细节见 [Windows 安装与配置](docs/LOCAL_SETUP.md)。

每个项目有一份长期工作副本；多段对话分别保存历史，共享这份副本的文件。导入方式和使用流程：

1. 在「新建或导入项目」中选「从本机导入」，点「选择文件夹…」打开 Windows 目录选择器。确认项目名称后点「创建工作副本」。也可用「手动输入路径」填写绝对路径。要从零开始，选「空项目」并填写名称。
2. 在项目对话中发送任务。可以继续发送纠正，或在需要时停止 Run。不同项目默认可同时运行两项任务。同项目的其他对话会排队，空闲后自动开始。
3. 选择「打开文件夹」用 Windows 编辑器查看、编辑同一份文件。默认路径为 `data\workbench\projects\<项目 ID>`。原始目录保留在导入位置。
4. 要预览网页，可请 Agent 启动项目的实际服务并登记预览，随后点击对话中的「打开本机预览」。服务退出或被替换后需要重新登记。[工作台使用说明](docs/WORKBENCH.md)

## 项目文档

- [Windows 安装与配置](docs/LOCAL_SETUP.md) · [项目、对话、工具与恢复](docs/WORKBENCH.md)
- [项目开发环境与并行调度（ADR-0029）](docs/decisions/0029-project-isolated-runtime.md) · [长对话压缩策略与验证](docs/WORKBENCH_CONTEXT.md)

工作台使用独立的控制数据库和项目工作副本。[产品](docs/PRODUCT.md)、[架构](docs/ARCHITECTURE.md)、[领域模型](docs/DOMAIN_MODEL.md)和[路线图](docs/ROADMAP.md)说明当前工作台的行为、边界与验收条件。
