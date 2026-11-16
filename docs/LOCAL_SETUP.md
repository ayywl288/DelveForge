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

1. 在原版本结束所有前台任务（包括「需要核对」），保存后台服务的状态，关闭控制端，再停止原环境的四个容器。将整个工作台数据目录复制到独立备份位置，包含 `control.db`、现有的 `-wal` 和 `-shm` 文件、环境登记、证书及 Windows 工作副本。保留旧容器、镜像和原卷。可在备份的另一份副本上运行 SQLite 的 `PRAGMA integrity_check` 并核对项目、对话、Run 和调用数量。
2. 用新版启动器打开原数据目录。默认安装直接运行 `.\setup-delveforge.ps1`。首次启动会将数据库升至 V7，旧 Run 的执行身份记为 `LEGACY`。项目第一次使用工具时，离线复制器从旧卷的 `/state/projects/<项目 ID>` 读取该项目的持久目录。旧共享 Maven 仓库和无法归属到项目的系统安装留在旧环境。
3. 迁移中断时，保留现场，在项目面板中用「核对维护」恢复已登记环境。要回退到旧版，关闭新控制端并停止新项目环境，将迁移后的整份工作台数据另行保存，再把升级前的完整备份恢复到原数据目录的绝对路径。用旧版本及原容器、卷启动；若从另一个检出启动旧版，通过 `-DataDirectory` 指定这个原路径。旧版读取恢复的 V6 数据库，升级后新增的对话和文件仍在另存的数据中。

<a id="4-自定义配置"></a>

开发镜像预装 Git、curl、ripgrep（`rg`）和 unzip。可直接搜索工作副本、解压归档。其他项目工具仍可按需安装。

## 全局工具镜像

当前支持 Ubuntu Noble 基础镜像自带签名软件源中的 APT 命令包。宿主从干净基础镜像下载指定版本并核对归档 SHA-256、发行版及可执行文件。只有受信下载容器可写构建目录。宿主离线构建后，再在无网络、无项目挂载的容器内验证命令。预览期间关闭面板或取消，不会变更全局镜像。用户安装的未知命令及无法验证来源的包在私有环境继续使用。

`tool-images.json` 保存成功发布的不可变镜像 ID、基础镜像、归档版本及哈希。每个项目最多保留一份当前推广预览，下一次预览会回收旧预览的归档。基础镜像系列变化后，从新的受信基础镜像重新推广。恢复旧项目容器前不得删除其镜像、容器或私有卷。

## 模型和执行设置

启动器参数控制 Windows 数据目录、后端端口和环境的停启方式。`delveforge.workbench.requests-per-run` 指定整项任务的默认请求总上限，默认 96。若只设置旧键 `requests-per-continuation`，其值作为总上限。每个前台命令的超时由 `delveforge.workbench.command-seconds` 配置，默认 300 秒。可在侧栏的「模型与用量」查看已解析的模型配置。

开发环境中的 shell 和 JVM 都使用各项目的持久化 HOME。Maven 从这里读取 `.m2/settings.xml`。默认本地仓库位于本项目的私有卷 `/state/cache/maven`，公共和自定义仓库的下载方式见下节。Maven Wrapper 的发行包按项目保存在 `$MAVEN_USER_HOME/wrapper`。普通重启和镜像重建都保留这些文件。

## 依赖下载与缓存维护

新项目的 Maven 默认配置把仓库 ID `central` 指向本机固定来源代理，上游始终是经 TLS 校验的 `https://repo.maven.apache.org/maven2`。其他仓库 ID 仍按项目或用户配置解析。需要自行定义 `central`、使用公司镜像或发送仓库凭据时，使用自己的 `settings.xml`。已有配置会保留。对已有项目，可用 `mvn -s "$HOME/.m2/delveforge-central.xml" …` 显式选择新配置。项目第一次初始化或在新版工作台运行工具时提供这份模板。

共享卷保存公共发布制品的下载响应。元数据与 SNAPSHOT 每次向上游查询，错误响应不会作为制品缓存。包体与校验值在 Maven 侧按强制校验策略验证。项目的 `mvn install`、私有仓库、本机凭据以及上线前已有的混合 Maven 目录都不会写入共享卷。离线模式读取本项目已经解析的文件，冷项目需要先联网取得构建所需插件和依赖。

npm 缓存、pnpm 内容存储和 Maven Wrapper 发行包均保存在项目自己的 Linux 数据中。npm/pnpm 只在本项目内复用其安装缓存，不混用 Windows 原生二进制。APT 的安装数据库位于容器系统层。项目间公用工具采用已核验的全局镜像。APT 下载归档仅用于对应的显式推广预览。

在 Windows 中可以查看或清空这个工作台的 Central 下载缓存：

```powershell
.\runtime\workbench\Reset-DownloadCache.ps1 -DataDirectory .\data\workbench -Action Status
.\runtime\workbench\Reset-DownloadCache.ps1 -DataDirectory .\data\workbench -Action Clear
```

清空会短暂中断共享下载服务，宜在没有在线构建时执行。脚本核对容器 ID、标签和卷后，只在专用下载卷内清除缓存文件。默认磁盘上限约 2 GiB，30 天未访问的对象自动淘汰。项目的 HOME、本地仓库、工作副本和工具镜像不参与此操作。

## 开发验证

后端在仓库根目录执行 `.\mvnw.cmd verify`。前端在 `frontend` 目录执行 `pnpm test`、`pnpm typecheck`、`pnpm build`。真实 Docker 用例使用独立数据目录，准备命令和预期结果见[项目隔离运行时的验证](WORKBENCH_RUNTIME_VALIDATION.md)。

[项目、工具和恢复说明](WORKBENCH.md) · [项目环境的架构决定](decisions/0029-project-isolated-runtime.md)

<a id="agent-build-preflight"></a>

已发布版本使用的 Agent Build Preflight 及镜像配置保存在对应版本的这份文档中。可在本地执行 `git show 2de40c0:docs/LOCAL_SETUP.md` 查看重构前的说明。
