# DelveForge Coding Agent 评测

首次基线有 6 个有效完成的 Run，6 份补丁均取得官方评分，4 份 Resolved、2 份 Unresolved。每题计一次有效试验，内部解决率为 4/6。逐题评分、历史中断、Gin 的产物恢复和证据摘要见[六题基线记录](RESULTS.md)。

在 Windows 本机按固定预算运行 SWE-bench Multilingual 子集。一次试验使用一个新 Project、一段 Conversation 和一个 Run。宿主 Python 只编排 Workbench HTTP、读取公开回执并交付补丁。评分使用上游的 `load_task`、`make_test_spec` 和 `run_instance`。官方测试容器和 Agent 容器分别创建。所有基准源码、Docker 镜像以外的生成物及日志均位于 Git 已忽略的 `target/eval-assets/`。

## 复现免费检查

从仓库根目录运行以下 PowerShell 命令。需要 Python 3.11+、Git、Java 21、可运行 Linux 容器的 Docker Desktop，以及端口 18943。首次准备需要联网下载官方镜像和依赖。

```powershell
python eval/runner.py setup
python eval/runner.py build-agent
.\eval\start-workbench.ps1
python eval/runner.py dry-run
python -m unittest discover -s eval/tests -v
python eval/runner.py preflight
python eval/runner.py report
```

`build-agent` 在独立源码副本运行 `mvnw.cmd clean verify`，记录 HEAD、当前产品变更摘要和实际 JAR 的 SHA-256。`start-workbench.ps1` 输出专用 Java 进程的 PID。在本机工作台已有用户项目的情况下也应单独启动评测服务。等待 `http://127.0.0.1:18943/api/workbench/health` 返回 UP 再导入。服务运行时可直接重复使用 `dry-run`、`preflight`、`report`。完成后可在 PowerShell 使用 `Stop-Process -Id <启动时输出的评测服务PID>`。

`dry-run` 核对定义并准备基线，不提交消息。`preflight google__gson-1014` 只检查指定任务。全套 preflight 会处理每个候选，有任何未准入项时退出码为 1。每个任务的 `checks/<instance>/admission.json` 记录判定，`noop/`、`oracle/` 保存原始官方报告、实际运行 ID 和测试日志。重新运行会复核导入并复用同一任务、镜像和评分工具的现有对照证据。

任务定义版本为 `6e08cbcd763c869abd7aae4bcb9e1b3991180777`，评分器版本为 `02e7a74ffd0b707aab73d203fe87bdc7c76afc8e`。各任务的仓库、完整基线提交、linux/amd64 镜像摘要均在 [suite.json](suite.json)。上游声明的镜像标签为 `latest`，实际评分按摘要读取。评分客户端本身记录构建出的 Docker 镜像 ID。重新构建时上游传递依赖的版本可能变化，应重新执行准入。[官方任务仓库](https://github.com/SWE-bench/swe-bench-multilingual-tasks/tree/6e08cbcd763c869abd7aae4bcb9e1b3991180777) · [官方评分器](https://github.com/SWE-bench/SWE-bench/tree/02e7a74ffd0b707aab73d203fe87bdc7c76afc8e)

## 本机免费验证结果

以下均为对照组。八个基线均已从实际 Workbench API 导入，并经过对象集合检查和空补丁回放。另在宿主独立夹具应用八份参考修复，导出非空补丁后逐一比对重建的 Git tree ID。Gson 1014 的导出补丁还走了一次正式试验补丁的交付与评分路径，官方判为解决。该次记录位于 `checks/google__gson-1014/export-smoke/`。

| 任务 | No-op | Oracle | 准入 |
|---|---|---|---|
| google__gson-1014 | 未解决 | 解决 | 是 |
| google__gson-2479 | 未解决 | 解决 | 是 |
| javaparser__javaparser-4538 | 未解决 | 解决 | 是 |
| gin-gonic__gin-2755 | 未解决 | 解决 | 是 |
| gin-gonic__gin-1957 | 官方新增测试引用基线缺少的两个方法，编译退出 1 | 解决 | 是，保留编译日志 |
| axios__axios-4738 | 目标断言失败 | 四条断言通过，官方 10 秒命令退出 124，评分器判未解决 | 否 |
| axios__axios-5316 | 加载官方测试缺少 formdata-node，未取得目标测试状态 | 解决 | 否 |
| vuejs__core-11915 | 未解决 | 解决 | 是 |

## 模型与预算

固定组合为 OpenAI Responses、`gpt-6.2-sol`、`high`。供应商文档支持此组合，内置与动态模型目录沿用产品原有能力筛选，模拟 Responses 服务的两轮测试核对模型名、思考强度、工具调用和回执。账户的实时兼容性需另行用真实凭据验证。为专用 Workbench 安全配置 OpenAI 凭据，例如让其启动进程继承已配置的 `OPENAI_API_KEY`。脚本无需密钥参数。[模型文档](https://developers.openai.com/api/docs/models/gpt-6.2-sol)

**以下两条命令会产生 API 费用，仅在取得单独授权后运行。**

```powershell
python eval/runner.py validate-model --authorize-paid
python eval/runner.py trial google__gson-1014 --authorize-paid
```

每项最多 96 个实际模型请求（含重试与压缩），Agent 执行时间 45 分钟，2 CPU、3072 MiB、768 PID，串行执行。宿主模型 HTTP 超时 90 秒，单条 Bash 默认 300 秒。进行中可通过专用 Workbench 的 Conversation API 查询请求数、累计 tokens 和状态。前台结清后，Runner 将最终 Run 记录和时间线导出到 `run.json` 与 `trace.json`。

预算限定模型请求数和执行时间。实际美元费用取决于输入、输出和缓存的计费明细，本次基线只取得逐 Run 的总 tokens。

## 执行与取证

- 固定包围基线的 Git 对象。用 `fetch --depth=1 --no-tags` 精确获取一个提交，不保存远端。导入前后核对 HEAD、引用、reflog、未跟踪与忽略文件，以及所有对象与该提交可达对象的集合。只将此目录交给 `ProjectWorkcopies.prepare()`。官方指令取自固定版本的问题文本。参考修复、测试补丁、评分器与隐藏资产留在宿主评分区域。
- 每项付费试验只有一个持久身份。创建操作先保存意图，以唯一名称恢复项目和对话；有歧义时停止。项目、对话、requestId、固定配置和计时锚点须在投递前原子保存。恢复只查询原对话；若提交回执丢失，须能从该专属对话唯一匹配原消息及 Run，否则停下核查。进度仅在观察到状态变化等节点尽力写入，写入错误输出到标准错误并保留在后续可写的清单中。准备时间单列，开始前锁定配置、校验账户已验证的 effort、资源限额、环境身份与下载策略。全套使用相同运行配置和镜像。继续同一条 CLI 命令会恢复观察或完成已有产物的交付。
- Run 的 `started_at` 实际在入队时写入。Runner 用单调时钟和轮询观测 QUEUED→RUNNING，按前一观测边界保守计时，排队时间单列。1 秒轮询和控制端响应时间会带来测量误差。Runner 缺席、主机休眠或服务失联期间，外部截止时间无法保证。恢复时交叉核对 UTC、单调时钟锚点和服务端入队或结束时间；可证明仍在预算内时继续观察同一 Run，无法建立可信界限时记录不确定并执行安全停止或请求介入。真正到期时先向原 Run 发 Stop，再记录结果。需要严格抗宿主中断的硬截止时间时，须在产品控制端实现持久期限。
- 超时向 Run 发 stop；BLOCKED 最多主动核对一次。STOPPING、RECONCILING 或未确认前台结束时不能导出。PAUSED 和预算耗尽均保留原状态，无自动继续、重复试验或追加预算。前台结清后，Runner 以已验证归属的完整容器 ID 停止该试验的开发容器，终止脱离前台的服务。其他项目和原始工作副本保留。
- 宿主在独立 Git 仓库中读取工作副本的索引和 Git 忽略规则。补丁包括已提交及未提交的实际修改、删除、改名、已索引文件和普通未跟踪新增文件。未跟踪且被 Git 忽略的路径记录于 `excludedPaths`。对交付内容保留凭据、链接、过滤转换与稳定性检查，发布前对照基线回放并验证整棵树。
- `trials/<instance>/` 保存 manifest、状态、公开时间线、逐调用的分页输出、补丁和官方评分日志。输出按通道保存 Base64 和原始分段长度，记录数据缺口和脱敏标志。私有模型部件及凭据存储不在采集范围内。公开工具输出和源码本身可能含敏感内容，内置脱敏覆盖常见凭据格式；保留本机证据，分享前检查。
- `toolCalls` 统计模型提议，`toolExecuted` 统计有执行证据的调用。回执按 SUCCESS、NONZERO_EXIT、TIMEOUT、NOT_STARTED、UNKNOWN 互斥计数，仅非零退出和工具超时计入 `toolFailures`。输出缺口与停止请求单独计数；原始回执继续保存在脱敏时间线中。

## 网络与结果解释


专用启动脚本从现有出站代理镜像派生评测镜像，只改专用安装目录的镜像目录。保留原有保留地址、私网、端口、CONNECT 与 nftables 防护；在这些规则之后使用 [下载域名表](downloads.acl) 默认拒绝其他目的地。每次运行前检查内部 Docker 网络、挂载、实际代理规则，并从项目容器验证 GitHub CONNECT 为 403、npm CONNECT 为 200。项目保有独立私有卷。包仓库、Go 模块代理和 Maven Central 缓存仍可能提供含公开修复的新版源码；模型训练数据也可能含答案。这是有明确污染边界的本地评测。更严的选项是预置经哈希固定的依赖与工具链，然后关闭全部外网，代价是逐任务维护离线依赖。

`reports/summary.{json,csv,md}` 逐候选显示结果。官方补丁解决率等于官方判为解决的补丁数除以取得有效官方结论的试验数。自主成功率等于正常、预算内完成且官方判为解决的试验数除以所有确认已启动的试验数。已启动后发生超时、评分失败或中断，均保留在自主成功率的分母中。超时补丁仍可计入官方解决数。仅创建试验或仍在排队不代表 Agent 已启动。报告列出候选、准入、暂缓、基础设施异常、未完成、前台未结清，以及已准入但尚未尝试的数量。两项比率的空分母均显示 0/0，JSON 比率为 null。
