# 项目隔离运行时的验证

## 多提供方和旧流程退役验收（2026-11-17）

四个 Maven 模块的默认回归执行 75 项，9 项 Docker 用例按环境单独启用。八家提供方的本地 HTTP 合同覆盖原生工具提议、调用 ID、参数和下一轮回执。真实 SQLite 测试覆盖 V7 升级、暂停恢复、排队时冻结模型、私有部件重启后重放、跨厂商续接与不同项目的并发请求。打包后的应用能重新打开测试项目和文件，旧工作流 API 返回 404。前端类型检查、生产构建与 33 项交互测试均通过。

真实 Docker 环境执行了 9 项。首次并行构建有一个项目未能下载 Maven 插件依赖；使用新测试项目复验后，通过了两个项目各自的构建、工具回执、预览与恢复断言。运行时的文件版本测试亦通过。

凭据验证使用打包后的工作台、独立控制数据库和官方端点。DeepSeek `deepseek-flash` 与 OpenAI `gpt-5.4-nano` 均完成 Ping 调用和回执后的回复，分别报告 593 和 151 token。智谱国内端点对 `glm-4.7-flash` 拒绝认证，本次未取得工具回合。其余五家本次只验了模拟协议。模型所列其他思考强度与账号权限需按组合验证。

## 此前的项目运行时验收（2026-11-16）

| 场景 | 实际观测 | 自动化复验 |
| --- | --- | --- |
| 并行与恢复 | 两个项目同时安装同一 Maven 坐标，消费程序分别输出 Alpha 和 Bravo。同一内部端口可分别通过 HTTP 和 WebSocket 访问。杀掉控制端后，两条真实命令在各自副本中各留下了一次副作用，队列随后继续执行。 | `ParallelProjectDockerTest`、`WorkbenchDockerTest`、`ProjectSchedulingTest` |
| 关闭控制端 | 工作线程收到中断后仍可完成最后的 SQLite 写入。控制端等待线程退出，关闭返回时可以从数据库读到最终状态。 | `WorkbenchIntegrationTest.closeWaitsForAnInterruptedWorkerToFinishItsDatabaseWrites` |
| 工具推广与回退 | 在一个项目安装并推广 `figlet 2.2.5-3`，新项目可用该命令。另一个已有项目在显式升级后才获得工具，回退后恢复原容器。私有脚本、HOME 标记和工作副本留在各自项目内。 | `ProjectToolImagesDockerTest` |
| 依赖与缓存 | 两个项目取得的 `org.json:json:20240303` JAR 摘要相同。首次回源接收 79,125 字节（含响应头），第二次命中本机缓存。同时请求 gson 源码包只回源一次。自定义仓库的同坐标文件按项目区分；损坏的 JAR 触发 Maven 严格校验失败。两个项目也分别在 Linux 用 npm 和 pnpm 安装、运行了 esbuild。 | `RemoteDownloadsDockerTest`、`WorkbenchDockerTest` |
| 对话与导入 | 在 Edge 的 1280 和 390 像素视口验证了运行中对话的圆环、完成后消失及减少动态效果设置。Windows 目录选择器实际完成了选择和取消，导入后编辑副本保留了源文件。 | `workbench-redesign.test.ts`、`ProjectWorkcopiesTest`、`SourceFolderSelectionTest` |

Docker 验收使用真实工具和确定性模型。工具推广界面的浏览器验收使用固定接口响应，软件安装、镜像构建与回退另由真实 Docker 用例验证。阶段全量后端回归通过 2,077 项，跳过 55 项，其中 8 项工作台 Docker 用例实际执行。随后新增的私有仓库来源用例也通过了 Maven 单项复验。完成侧栏改动后，前端 410 项通过，类型检查和生产构建通过。以上验收均未调用付费模型。

升级复验使用停止控制端后备份的 V6 数据。新版在独立检出中无参数启动，前后端均可访问；迁移后 9 张业务表的原有列和全部历史行逐项一致，17 个旧 Run 均保留原执行身份。旧版控制端从另一份 V6 恢复副本打开了相同的对话和时间线。旧 Linux 卷的项目目录复制另用专用 Docker 测试卷验证。

收尾提交 `4bc9962` 的默认离线 Maven 回归通过 2,070 项，64 项需要单独启用或准备外部仓库，失败和错误均为零。前端 410 项通过，类型检查与生产构建通过。无参数启动后，前端首页和代理健康请求均成功，工作台接口读出了原有 17 个 Run 和 179 条时间线记录。

## 真实 Agent 功能验收（2026-11-16）

在独立的 Room Desk 预约服务（Java 21、Spring Boot 3.5.16，Git 基线 `d33f15e75e31`）上，通过工作台对话提交一次开发任务。要求查询整个时段都空闲且容量足够的房间，并允许原子改约。提交前冻结了项目外部的 HTTP 检查。基线自带的 8 项测试通过，外部检查仅原有预约流程通过，7 组新功能检查均失败。

| 独立核验 | 结果 |
| --- | --- |
| 交付内容 | Agent 修改日历、房间目录、控制器、测试和 README，共 8 个文件，新增 555 行、删除 13 行，含 3 个新文件。独立检出复原了完整工作树 `01aed8061be4`。 |
| 正常构建 | 在独立检出中执行 Maven `verify` 并生成可执行 JAR。基线 8 项与新增 24 项全部通过。POM、房间容量和原有断言均保留。 |
| 实际 HTTP 行为 | 对新 JAR 的 8 组检查全部通过，覆盖容量排序、相邻时段、取消释放、保留预约 ID、自身重叠、400/404/409 和失败后的数据完整性。6 轮双请求争同一时段，每轮恰有一个 200、一个 409，并逐一读回两条预约。 |

Run `585930db-0ba5-40c6-8ba1-c1c446831929` 通过真实 Java 控制端、SQLite 和项目 Docker 环境执行，历时 10 分 53 秒。模型直连 DeepSeek 官方入口，使用 `deepseek-flash`、高强度思考、131,072 token 上下文和 16,384 token 输出上限。一次提交自然完成，使用 28/30 次请求，全部为首次尝试返回 HTTP 200。28 份提供方用量合计输入 696,108 token（缓存命中 677,760，未命中 18,348）、输出 25,691，与 Run 持久化的 721,799 token 一致。按执行时的高峰费率估算为 **0.04040016 美元**，预算为 5 美元。[DeepSeek 官方价格](https://api-docs.deepseek.com/quick_start/pricing/)

35 次工具调用包括 Bash 18 次、Write 7 次、Read 和 Edit 各 5 次。Agent 读取文件版本后恢复了两次写入，修正了新增测试对 JSON 时间秒位的预期，并在冷缓存缺少打包插件时用联网 Maven 完成构建。逐项核对了 35 份回执和 38,004 字节的捕获输出，输出缺口为零。31 次工具退出码为 0，3 次为 2。停止测试服务的命令返回 143，随后确认端口已关闭。有一次 Maven 断言失败被外层管道的退出码 0 遮住，Maven 输出和独立构建分别确认了失败与修正。

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
