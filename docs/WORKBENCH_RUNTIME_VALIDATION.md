# 项目隔离运行时的验证

## 已验证的行为

| 场景 | 实际观测 | 自动化复验 |
| --- | --- | --- |
| 并行与恢复 | 两个项目同时安装同一 Maven 坐标，消费程序分别输出 Alpha 和 Bravo。同一内部端口可分别通过 HTTP 和 WebSocket 访问。杀掉控制端后，两条真实命令在各自副本中各留下了一次副作用，队列随后继续执行。 | `ParallelProjectDockerTest`、`WorkbenchDockerTest`、`ProjectSchedulingTest` |
| 关闭控制端 | 工作线程收到中断后仍可完成最后的 SQLite 写入。控制端等待线程退出，关闭返回时可以从数据库读到最终状态。 | `WorkbenchIntegrationTest.closeWaitsForAnInterruptedWorkerToFinishItsDatabaseWrites` |
| 工具推广与回退 | 在一个项目安装并推广 `figlet 2.2.5-3`，新项目可用该命令。另一个已有项目在显式升级后才获得工具，回退后恢复原容器。私有脚本、HOME 标记和工作副本留在各自项目内。 | `ProjectToolImagesDockerTest` |
| 依赖与缓存 | 两个项目取得的 `org.json:json:20240303` JAR 摘要相同。首次回源接收 79,125 字节（含响应头），第二次命中本机缓存。同时请求 gson 源码包只回源一次。自定义仓库的同坐标文件按项目区分；损坏的 JAR 触发 Maven 严格校验失败。两个项目也分别在 Linux 用 npm 和 pnpm 安装、运行了 esbuild。 | `RemoteDownloadsDockerTest`、`WorkbenchDockerTest` |
| 对话与导入 | 在 Edge 的 1280 和 390 像素视口验证了运行中对话的圆环、完成后消失及减少动态效果设置。Windows 目录选择器实际完成了选择和取消，导入后编辑副本保留了源文件。 | `workbench-redesign.test.ts`、`ProjectWorkcopiesTest`、`SourceFolderSelectionTest` |

Docker 验收使用真实工具和确定性模型。工具推广界面的浏览器验收使用固定接口响应，软件安装、镜像构建与回退另由真实 Docker 用例验证。阶段全量后端回归通过 2,077 项，跳过 55 项，其中 8 项工作台 Docker 用例实际执行。随后新增的私有仓库来源用例也通过了 Maven 单项复验。完成侧栏改动后，前端 410 项通过，类型检查和生产构建通过。以上验收均未调用付费模型。

升级复验使用停止控制端后备份的 V6 数据。新版在独立检出中无参数启动，前后端均可访问；迁移后 9 张业务表的原有列和全部历史行逐项一致，17 个旧 Run 均保留原执行身份。旧版控制端从另一份 V6 恢复副本打开了相同的对话和时间线。旧 Linux 卷的项目目录复制另用专用 Docker 测试卷验证。

## 复验方法

在 Windows 的新 PowerShell 会话中进入仓库根目录，准备好 JDK、pnpm 和 Docker Desktop。普通回归可直接执行以下命令。[本地安装与启动](LOCAL_SETUP.md)

```powershell
.\mvnw.cmd verify
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend build
```

要执行真实工作台 Docker 用例，准备三个新的测试目录后再运行 Maven。并行项目、工具推广和冷下载测试各用一个目录。软件包安装与首次下载需要能访问对应的公共仓库。

```powershell
$testRoot = Join-Path (Get-Location) ("target/runtime-acceptance-" + [guid]::NewGuid())
$env:DF_WORKBENCH_TEST_FIXTURE = Join-Path $testRoot 'projects'
$env:DF_WORKBENCH_TOOL_FIXTURE = Join-Path $testRoot 'tools'
$env:DF_WORKBENCH_CACHE_FIXTURE = Join-Path $testRoot 'downloads'
foreach ($dir in @($env:DF_WORKBENCH_TEST_FIXTURE, $env:DF_WORKBENCH_TOOL_FIXTURE, $env:DF_WORKBENCH_CACHE_FIXTURE)) {
    .\runtime\workbench\Initialize-Environment.ps1 -DataDirectory $dir
}
.\mvnw.cmd verify
```

核对 Maven 模块的 Surefire 结果，确认四个工作台 Docker 测试类实际执行。清理测试环境时，用各自 `runtime-catalog.json` 中的 `owner` 核对 Docker 标签和登记的资源身份。[后端工作台用例](../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workbench) · [前端交互用例](../frontend/tests/workbench-redesign.test.ts) · [迁移与回退](LOCAL_SETUP.md#从共享环境升级) · [ADR-0029](decisions/0029-project-isolated-runtime.md)
