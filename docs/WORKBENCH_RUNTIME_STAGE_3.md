# 项目依赖隔离与公共下载复用验收

本阶段从 `63f79704d833fc0b8fb339a873e52836f8d483ec` 开始。运行 `git log -1 --diff-filter=A --format=%H -- docs/WORKBENCH_RUNTIME_STAGE_3.md` 可定位阶段提交。复验需要 Windows、Docker Desktop 的 Linux 引擎及可用的公共软件源。测试模型使用确定性替身。

## 复验

在仓库根目录运行。三个独立数据目录分别承载并行执行、工具推广和公共下载。全新的下载目录用于观察首次缺失。

```powershell
$testRoot = Join-Path (Get-Location) ("target/runtime-acceptance-" + [guid]::NewGuid())
$env:DF_WORKBENCH_TEST_FIXTURE = Join-Path $testRoot "projects"
$env:DF_WORKBENCH_TOOL_FIXTURE = Join-Path $testRoot "tools"
$env:DF_WORKBENCH_CACHE_FIXTURE = Join-Path $testRoot "downloads"
foreach ($dir in @($env:DF_WORKBENCH_TEST_FIXTURE, $env:DF_WORKBENCH_TOOL_FIXTURE, $env:DF_WORKBENCH_CACHE_FIXTURE)) {
    .\runtime\workbench\Initialize-Environment.ps1 -DataDirectory $dir
}
.\mvnw.cmd verify
```

只复验下载和 Wrapper 时，在新数据目录使用同样的初始化步骤，然后运行以下命令。

```powershell
.\mvnw.cmd '-Dtest=RemoteDownloadsDockerTest,WorkbenchDockerTest#mavenAndColdWrapperReadPersistentProjectHomeThroughTheProxy' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 实测

Windows 的独立验收目录为 `target/project-isolation-20261115`。首次专项的缓存容器为 `796f444ba1b7`，A 为 `a0cb366e-b4ee-48e7-8bbe-58462fcffade`，B 为 `bbbe8599-8a1f-468a-a6be-7d76d3a0d3b5`。下表采用缓存端的上游接收字节，含响应头。

| 公开制品 | 首个请求 | 另一个项目 | 内容 |
| --- | --- | --- | --- |
| `org.json:json:20240303` JAR | MISS，79,125 字节 | HIT，未回源 | 两份 SHA-256 均为 `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed` |
| `gson:2.11.0` 源码 JAR，两边同时请求 | MISS，204,602 字节 | HIT，未回源 | 两份 SHA-256 相同 |

- 两个 Maven 项目同时安装 `fixture:shared:1`。A 的 JAR 摘要以 `47a412c0` 开头，B 的以 `530d7a1c` 开头。消费程序分别输出 Alpha 和 Bravo。公共代理拒绝上传和带身份的请求，向 Central 查询该私有坐标得到 404。重复查询也未命中。
- 快照和元数据每次经过上游。刻意损坏专用缓存卷内的 JAR 后，使用新本地仓库的 Maven 严格校验失败。清空专用缓存后，B 先用已有私有仓库离线构建，再用新仓库在线重取并核对原摘要。A 重启及重建后继续用自己的同坐标 JAR 离线构建，项目自行修改的 Maven 设置摘要相同。测试还断开了 A 的缓存网络连接，下一次工具调用恢复连接。
- 两个项目均在 Linux 内以 npm 和 pnpm 安装固定版 esbuild、打包并实际加载自己的产物。npm 缓存和 pnpm 存储分别位于各自私有卷内的 `/state/cache/npm` 和 `/state/cache/pnpm/v11`。pnpm 测试项目显式授权 esbuild 的安装脚本。[pnpm 项目构建授权](https://pnpm.io/cli/approve-builds)
- 冷 Maven Wrapper 使用项目的持久 HOME、Linux JDK 和项目配置下载 Maven 发行包，并通过本机 Central 代理取得插件。仅具有完整本地依赖和 Wrapper 发行包的项目可离线构建。
- APT 以经过验证的工具镜像复用已安装的软件。运行中项目的 APT 数据库和安装层独立。历史混合 Maven 缓存留在原卷。npm、pnpm、公司 Maven 仓库和用户配置继续按项目维护。[使用与维护](LOCAL_SETUP.md#依赖下载与缓存维护)

最终代码还使用两个不同的项目私有文件仓库发布同一坐标。Maven 在每个项目记录的来源均为 `project-private`，取出的 JAR 各自带有正确的项目标记。公共代理对同坐标返回 404。第二套下载环境再次观测到公开 JAR 首次回源 79,125 字节，另一项目命中。复验移走 B 的单个目标 JAR，再确认严格校验失败时私有仓库没有接纳坏文件，随后用 `-U` 取回正确内容。

在已推广 figlet 的环境中，公共下载目录从 92 KiB 清空。请求在清理前为 HIT，随后为 MISS。项目文件及工具清单的 SHA-256 在清理前后相同，镜像保持 `sha256:b491f365ff69838e269bee8b2200de03b378260be9b669079638f055ce2cd5b2`，项目中执行 `figlet -I1` 仍得到 `20205`。记录见 `s3-clear-boundary.log`。

首轮下载及冷 Wrapper 专项 2 项通过，最终下载和自定义来源专项 2 项通过，均跳过 0 项。原始日志为 `s3-cache-final.log`、`s3-exact-cache.log`，传输记录为 `stage3b/transfer-evidence.json`、`stage3e/transfer-evidence.json`。这些文件在上述验收目录内。

## 全量回归

本机运行 `mvnw.cmd verify`，共发现 2,132 项，2,077 项通过，55 项跳过，失败与错误均为 0。当前工作台的 8 项真实 Docker 测试全部执行，覆盖并行构建、进程恢复、预览、工具镜像及下载缓存。跳过项属于另行启用的历史 Docker 镜像、公开仓库矩阵和真实模型验收。逐项原因保存于 `full-backend-results.json`，完整构建记录为 `s3-full-verify.log`。本轮真实模型调用为 0。

全量回归与最终补充用例分开计数。最终版两个缓存用例另外经过 JUnit 运行，私有来源用例又以仓库标准入口 `mvnw.cmd -pl backend/delveforge-infrastructure -am '-Dtest=RemoteDownloadsDockerTest#customRepositoryIdentityAndBytesStayWithTheProject' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过，记录为 `s3-private-origin.log`。阶段前端基线 408 项全部通过，类型检查和生产构建通过。
