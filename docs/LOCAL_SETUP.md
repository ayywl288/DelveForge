# Windows 本地安装与启动

## 准备

使用 Windows PowerShell 5.1 或 PowerShell 7。安装 Git、JDK 21、Node.js（`^20.19.0 || >=22.12.0`）、pnpm 12.9.1，以及启用 Linux 容器的 Docker Desktop。仓库自带 Maven Wrapper，数据库为 SQLite。环境使用 Docker Desktop 的本机命名管道、隔离桥接网络和回环端口发布。本次验证使用 Docker Engine 29.4.0、API 1.54。

在仓库根目录运行以下命令，然后打开 [本机工作台](http://127.0.0.1:5173)。

```powershell
.\setup-delveforge.ps1
```

脚本检查端口、运行环境和依赖，准备受信镜像目录，启动后端与前端，并等到实际服务可访问。模型密钥可在脚本的隐藏输入提示中提供，也可通过当前进程的 `DEEPSEEK_API_KEY` 提供。只有后端服务需要该值。前端使用 5173，后端默认使用 8080。

## 数据位置

默认使用 `data\workbench\`。其中 `control.db` 保存项目、对话、消息、Run、调用与已采集输出，`projects\<项目 ID>\` 是用户可直接打开的 Windows 工作副本。控制证书仅挂载到受信组件。首次执行工具时才创建项目环境。原共享容器、卷及环境登记保留，升级前请参照下文备份。

可以从第一次使用起固定数据目录和后端端口。对同一数据目录只启动一个控制端。

```powershell
.\setup-delveforge.ps1 -DataDirectory 'D:\DelveForgeData' -BackendPort 8088
```

## 停止、恢复和更新环境

在前后端各自终端按 Ctrl+C 可关闭工作台。重新执行同一条启动命令，保留原开发容器、Windows 工作副本和对话。控制端启动后会核对中断的前台调用。已登记的预览依赖网关和实际服务继续运行。

普通启动只准备本机镜像目录。一个项目在第一次调用工具时独立创建开发容器和网络。打开「工作副本」中的环境操作，可以重启或重建此项目。重启会结束该项目的后台服务，保留系统软件。重建验证新容器后再停用原容器，工作副本及私有卷继续使用。需要核对的命令必须先在原环境解决，才能重建。

### 从共享环境升级

先在原版本结束所有前台任务（包括「需要核对」），保存后台服务，关闭控制端，再停止原环境的四个容器。停止控制端后备份整个 `data/workbench`，包括 SQLite 的日志文件、环境登记与 Windows 工作副本。保留原 Docker 容器及卷。新版本首次启动会就地增加调度表和字段，保留历史和原执行身份。项目第一次使用时由离线复制器读取旧卷的 `/state/projects/<项目 ID>`，复制到本项目独占卷。旧共享 Maven 仓库和无法归属到项目的系统安装留在旧环境。

迁移中断后，保留数据和卷并重新启动。在项目面板中执行「核对维护」恢复已登记环境。回退到旧版时，先关闭新控制端并停止新项目环境，另存迁移后产生的对话和文件，然后使用升级前的数据库备份及原容器。旧版无法解释新队列。

<a id="4-自定义配置"></a>

开发镜像预装 Git、curl、ripgrep（`rg`）和 unzip。可直接搜索工作副本、解压归档。其他项目工具仍可按需安装。

## 全局工具镜像

当前支持 Ubuntu Noble 基础镜像自带签名软件源中的 APT 命令包。宿主从干净基础镜像下载指定版本并核对归档 SHA-256、发行版及可执行文件。只有受信下载容器可写构建目录。宿主离线构建后，再在无网络、无项目挂载的容器内验证命令。预览期间关闭面板或取消，不会变更全局镜像。用户安装的未知命令及无法验证来源的包在私有环境继续使用。

`tool-images.json` 保存成功发布的不可变镜像 ID、基础镜像、归档版本及哈希。每个项目最多保留一份当前推广预览，下一次预览会回收旧预览的归档。基础镜像系列变化后，从新的受信基础镜像重新推广。恢复旧项目容器前不得删除其镜像、容器或私有卷。

## 模型和执行设置

启动器参数控制 Windows 数据目录、后端端口和环境的停启方式。`delveforge.workbench.requests-per-run` 指定整项任务的默认请求总上限，默认 96。若只设置旧键 `requests-per-continuation`，其值作为总上限。每个前台命令的超时由 `delveforge.workbench.command-seconds` 配置，默认 300 秒。可在侧栏的「模型与用量」查看已解析的模型配置。

开发环境中的 shell 和 JVM 都使用各项目的持久化 HOME。Maven 从这里读取 `.m2/settings.xml`，通过已有出口代理下载，默认本地仓库位于本项目的私有卷 `/state/cache/maven`。Maven Wrapper 的发行包按项目保存在 `$MAVEN_USER_HOME/wrapper`。普通重启和镜像重建都保留这些文件。

## 开发验证

后端执行 `./mvnw.cmd verify`。前端在 `frontend` 目录执行 `pnpm test`、`pnpm typecheck`、`pnpm build`。产品 Docker 集成测试须另外准备独立的测试数据目录，使用初始化脚本建立其环境，再将 `DF_WORKBENCH_TEST_FIXTURE` 指向该目录。测试会在此文件夹中创建临时项目。

[项目、工具和恢复说明](WORKBENCH.md) · [项目环境的架构决定](decisions/0029-project-isolated-runtime.md)

<a id="agent-build-preflight"></a>

已发布版本使用的 Agent Build Preflight 及镜像配置保存在对应版本的这份文档中。可在本地执行 `git show 2de40c0:docs/LOCAL_SETUP.md` 查看重构前的说明。
