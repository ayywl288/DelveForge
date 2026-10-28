# MVP 冻结与本地发布候选

日期：2026-10-28（Asia/Shanghai）。状态：**MVP_FROZEN / LOCAL_RELEASE_CANDIDATE**，未合并、未推送、未发布。

本次只整理文档、证据入口与构建启动验证。原四步与失败历史保留，不新增 EvolutionPlan 或付费 Run；
先前提出的 C4 CSRF 修复计划不再执行。软件版本号仍为 0.1.0-SNAPSHOT，没有创建新发布标签。

## 基线核对

| 对象 | 本轮核实的基线 | 处理 |
| --- | --- | --- |
| main | `80cd124aea3579afd97cbb7fa9affb913078ee63` | 保持不变 |
| 原完整实现分支 | `codex/resolve-step4-blockers`，`11c4aa3cc8efd80314637376b3cdf60669ddb07f` | 保留原分支指针 |
| 最后运行代码提交 | `1b9baa6` | 原四步收尾修复与完整回归；后续 11c4aa3 只补报告证据 |
| 本地候选 | `codex/mvp-freeze-release`，从 11c4aa3 建立 | 只增加当前说明与安全验证摘要；最终提交号用 `git rev-parse HEAD` 获取 |
| FinTrack | `codex/mvp-fintrack` / `b57cdbb` | 已是 11c4aa3 祖先，无需另行合并 |
| 仓库兼容性 | `codex/repository-compatibility` / `80cd124` | 已包含 |
| 独立早期 FinTrack 报告分支 | `codex/fintrack-full-product-e2e` / `0278e82` | 唯一未包含的提交只新增历史报告；原 worktree/分支保留，不混入新实现 |

main 是 11c4aa3 的祖先：0 个 main 独有提交、31 个实现分支后续提交；
其中包含 Agent 源码编辑/纠正、凭据政策、Maven 准备及检查隔离、Delivery 收尾、前端聚合与清理。
后续文档提交不改变这些运行源码。不是从多个分支盲目 cherry-pick 或拼接一个未测实现。

六个既有 worktree 均保留。本次在原多步实验开发 worktree 建立候选分支，
没有重置或清理其它 checkout。main 原有 `frontend/design/` 与 `grilling/` 共八个草稿保持，
历史数据库、目标 WorkingCopy、Docker 卷和验收证据未作为本次启动数据使用。

## 核对与可复现性

新执行结果见 [冻结验证摘要](../validation/evidence/mvp-freeze-verification.json)。

- 后端离线 `.\mvnw.cmd -o -DskipTests package` 成功，明确跳过测试，不把打包称为新测试通过。
- 前端 `pnpm --dir frontend test` 269/269、`pnpm --dir frontend build`（包含类型检查）通过。
- 使用新建独立 SQLite、独立 Workspace/Agent roots、无 Provider key、Agent disabled 启动新 JAR。
  后端 connectivity、空项目 API、Vite 首页及经 Vite 的 API 代理均通过；迁移到 V26。
  本轮未做新的浏览器渲染或付费模型验证。
- 后端完整 verify 的 1735 总计 / 1696 执行通过 / 39 跳过来自 `1b9baa6` 的历史证据。
  本次运行源码与该版本相同，文档整理不触发另一轮广泛实验。

原始日志、启动库、端口/PID 和本机保护清单保留在 Git 忽略的数据目录；
提交的摘要只含结果、版本、计数和包摘要，不含 API key、数据库或完整本机运行日志。
扫描确认 46 份既有历史文件含本机环境路径，保持历史原貌；本次新增说明与摘要没有机器专属路径。
这些旧路径只作历史定位，不能当作别人机器上的运行指令；对外演示使用当前概览、图和安全摘要，原始日志和数据库不随材料分发。

## 以后集成到 main 的建议

**当前不执行合并或推送。** 用户批准集成后，再核对：

1. `git status --short` 和 `git rev-parse HEAD` 确认候选干净；记录 main 和候选精确提交。
2. 比较 main 的未跟踪路径与候选新增路径，保留草稿；不得使用 force checkout、reset 或 clean。
3. 当前 main 是候选祖先，预期可以 fast-forward；核实 `git merge-base --is-ancestor main codex/mvp-freeze-release`。
4. 正常停止真实实例；一致备份 SQLite、全部托管副本（含隐藏 Git、未跟踪文件）、
   Agent 留存材料、配置与旧 JAR。先用隔离环境验证，不能启动指向真实副本的克隆库。
5. 经明确批准后，才在 main checkout 执行 `git merge --ff-only codex/mvp-freeze-release`。
   若 main 已前进导致无法快进，在新的隔离集成分支审查冲突并验证变化；不要自动改用强制或普通 merge。
6. 使用对应环境构建、启动并检查 API 与 UI。推送、tag、Release 均是之后单独的发布动作。

当前没有分支历史冲突；真正切换 main 尚未执行。
文档候选不引入新迁移，但 main 到候选包含长期累积的流程变化，不能仅凭“能快进”省略数据备份与启动核对。

## 回退与保留

集成前的回退是继续使用未改变的 main 或原实现分支，无须改数据库。
运行过新版后，代码回退和数据恢复要一起评估；Flyway 没有通用降级，不能只替换 JAR 或只回退 Git ref。
保留集成前提交、旧包与配套一致备份，在独立路径恢复并先检查；未知占用、部分交付保留现场。
不删除本次以外的 worktree、数据库、Agent 材料或实验资源。

## 交接判断

可展示的内容是：需求到方向/计划的流程、受控工具循环、快照绑定验证、冻结 Candidate、
Git/SQLite 协调以及真实成功与失败实验。FinTrack 可作为核心使用证据，Taskarium 展示多步交付与验收差异。
**不把 Taskarium 注册/登录 403 包装成全产品通过，也不宣称生产可用或无人值守。**

演示顺序与面试表述见 [DEMONSTRATION](DEMONSTRATION.md)，后续 UX 反馈进入 [BACKLOG](UX_BACKLOG.md)，不在冻结期实现。
