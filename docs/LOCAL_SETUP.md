# Windows 本地安装与启动

## 准备

使用 Windows PowerShell 5.1 或 PowerShell 7。安装 Git、JDK 21、Node.js（`^20.19.0 || >=22.12.0`）、pnpm 12.9.1，以及启用 Linux 容器的 Docker Desktop。仓库自带 Maven Wrapper，数据库为 SQLite。环境使用 Docker Desktop 的本机命名管道、隔离桥接网络和回环端口发布。本次验证使用 Docker Engine 29.4.0、API 1.54。

在仓库根目录运行以下命令，然后打开 [本机工作台](http://127.0.0.1:5173)。

```powershell
.\setup-delveforge.ps1
```

脚本检查端口、运行环境和依赖，建立共享 Linux 环境，启动后端与前端，并等到实际服务可访问。模型密钥可在脚本的隐藏输入提示中提供，也可通过当前进程的 `DEEPSEEK_API_KEY` 提供。只有后端服务需要该值。前端使用 5173，后端默认使用 8080。

## 数据位置

默认使用 `data\workbench\`。其中 `control.db` 保存项目、对话、消息、Run、调用与已采集输出，`projects\<项目 ID>\` 是用户可直接打开的 Windows 工作副本。控制证书仅挂载到受信组件。旧版本数据库和既有工作副本不参与新工作台的启动或迁移。

可以从第一次使用起固定数据目录和后端端口。对同一数据目录只启动一个控制端。

```powershell
.\setup-delveforge.ps1 -DataDirectory 'D:\DelveForgeData' -BackendPort 8088
```

## 停止、恢复和更新环境

在前后端各自终端按 Ctrl+C 可关闭工作台。重新执行同一条启动命令，保留原开发容器、Windows 工作副本和对话。控制端启动后会核对中断的前台调用。已登记的预览依赖网关和实际服务继续运行。

普通启动会先核对现有容器使用的镜像，再询问模型密钥。遇到镜像更新时，交互终端会列出需要更新的组件。输入 `y` 会重建并继续启动，直接回车会保留原环境并退出。在 Docker Desktop 里停止容器后，它仍绑定原镜像。

关闭工作台后，也可以直接选择以下操作。自定义数据目录时须传入原来的 `-DataDirectory`。

```powershell
.\setup-delveforge.ps1 -EnvironmentAction Restart
.\setup-delveforge.ps1 -EnvironmentAction Rebuild
```

`Restart` 会结束共享环境中的后台进程，保留容器内安装的软件。版本不匹配时，`Restart` 会先退出。`Rebuild` 会替换容器，需要重新安装额外装在系统层的软件。两种操作都保留 Windows 工作副本、对话数据库和项目数据卷。

<a id="4-自定义配置"></a>

开发镜像预装 Git、curl、ripgrep（`rg`）和 unzip。可直接搜索工作副本、解压归档。其他项目工具仍可按需安装。

## 模型和执行设置

启动器参数控制 Windows 数据目录、后端端口和环境的停启方式。`delveforge.workbench.requests-per-run` 指定整项任务的默认请求总上限，默认 96。若只设置旧键 `requests-per-continuation`，其值作为总上限。每个前台命令的超时由 `delveforge.workbench.command-seconds` 配置，默认 300 秒。可在侧栏的「模型与用量」查看已解析的模型配置。

开发环境中的 shell 和 JVM 都使用各项目的持久化 HOME。Maven 从这里读取 `.m2/settings.xml`，通过已有出口代理下载，默认下载仓库为共享卷上的 `/state/cache/maven`。Maven Wrapper 的发行包按项目保存在 `$MAVEN_USER_HOME/wrapper`。普通重启和镜像重建都保留这些文件。

## 开发验证

后端执行 `./mvnw.cmd verify`。前端在 `frontend` 目录执行 `pnpm test`、`pnpm typecheck`、`pnpm build`。产品 Docker 集成测试须另外准备独立的测试数据目录，使用初始化脚本建立其环境，再将 `DF_WORKBENCH_TEST_FIXTURE` 指向该目录。测试会在此文件夹中创建临时项目。

[项目、工具和恢复说明](WORKBENCH.md) · [共享环境的架构决定](decisions/0028-project-conversations-and-shared-environment.md)

<a id="agent-build-preflight"></a>

已发布版本使用的 Agent Build Preflight 及镜像配置保存在对应版本的这份文档中。可在本地执行 `git show 2de40c0:docs/LOCAL_SETUP.md` 查看重构前的说明。
