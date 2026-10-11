# Task E — Final E2E UX Polish（含 E4 换盘配置）

2026-10-11。开发基线为 Task D `bab279a39488a3a77ea2ff01e5ce128e86b26d50`，
独立分支 `codex/task-e-final-e2e-ux`。本轮只完善许可证声明、授权导航、执行等待展示及
WorkingCopy 存储配置，不优化 Agent/Prompt/Patch，不改变 Candidate 或 Verification 领域规则。
没有调用付费 Provider、运行真实 Smoke、修改业务数据库或 GS REST 演示仓库，也没有 push。
用户此前报告的 GS REST Verified B 不是本轮自动化测试产生的结果。

## E1 — 许可证是显式声明

原来 SoftwareAsset 新建/更新只拒绝空白，因此 `123` 可成为许可证记录。
新增回归在修复前实际失败：27 项 Domain 测试中 2 项失败，证明新建和更新均未拒绝纯数字。
修复后只在 `create` 与 `updateAuthorization` 检查非 null 声明至少含 Unicode 文字字符。
`null` 继续表示未知；空白、纯数字、纯符号提交返回 `400 / INVALID_REQUEST`。
这是输入底线，不是许可证真实性、权属、法律兼容性或商用资格鉴定，任意含文字的错误声明
仍可能通过。因此用户仍须核对实际条款并显式提交读取和使用授权。

注册和授权编辑共用许可证控件：MIT、Apache-2.0、BSD-3-Clause、GPL-3.0-only，及
「其他 / 自定义 / 混合许可证」。自定义文本可分别记录代码与文档的条款、来源及范围，
保持现有 `licenseInfo` 字符串契约；例如代码许可不自动扩展到教程文字。
SPDX 选择不修改读取许可或使用授权，授权编辑仍须明确核对后才能提交。

历史 `reconstitute`、已有 ID 和 authorizationRevision 不做追溯格式增强。
旧数字文本可忠实读取，不自动改写；再次提交时需要明确纠正或声明未知。
没有数据迁移，没有重判旧 Plan/WorkingCopy/Verified State 的许可证输入格式。
授权更新的 Task D CAS、失效旧版本及失败不留下部分变更继续适用。
实现见 [SoftwareAsset](../../backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/asset/SoftwareAsset.java)
及 [许可证控件](../../frontend/src/components/LicenseDeclarationField.vue)。

## E2 — 按当前快照的 Asset ID 补充授权

Planning 从用户选择的 RepositoryProfile 取 `assetId`，显示「前往补充许可证与授权」。
服务端因读取许可、使用授权、许可证或授权版本拒绝规划时，也保留对应资产的入口；
普通状态冲突不伪装成授权问题。
点击先 GET 精确资产，成功才跳转；NOT_FOUND 留在原页显示真实错误，不改选同路径记录。
Repository 页面只展开和聚焦该 ID 的编辑区，保存成功后提供「返回演化规划」。
KeepAlive 保留原基础快照选择；更新没有自动分析、方向发现或规划调用。
失败保存没有成功返回按钮，授权版本冲突仍读取当前授权、不自动重发。
实现见 [App 导航](../../frontend/src/App.vue)、[PlanView](../../frontend/src/views/PlanView.vue)
及 [RepositoryView](../../frontend/src/views/RepositoryView.vue)。

## E3 — 前端等待与后台状态分开

execute POST 发送后，立即提示「执行请求已提交，正在等待后端结果」，按实际发起时间
显示秒/分钟。计时不代表后台百分比或阶段；只显示 GET 返回的 Step、Candidate 与已保存检查。
本页约每 3 秒查询（上一查询完成后再等 3 秒），只读查询不重发 execute。
可暂停；离开缓存页面或卸载清理轮询和计时，返回只恢复 GET。
底层请求继续等待，不因为离页取消后台操作或抹去等待标记。

SUCCEEDED 且该 Candidate 等于 lastVerifiedRevision 时，突出真实 revision 和检查结果。
BUILD、TEST 等条目按响应原样显示，没有返回的检查不会补成 PASS。
断线或刷新后，浏览器等待标记继续阻止执行；READY 不证明 CodingProposal 准备已结束。
尚未读到 Plan 时也提供安全查询入口，不误导用户重新开始执行。
没有新增 M4 状态、异步任务、进度日志、超时重试或下一 Step 自动执行。
实现见 [会话与轮询](../../frontend/src/state/session.ts) 和
[EvolutionView](../../frontend/src/views/EvolutionView.vue)。

## E4 — 新 Root 与旧副本目录分开配置

原来已经支持 `DELVEFORGE_WORKSPACE_ROOT`，但托管检查只认可当时的单一 Root，切换后
操作旧副本会被拒绝。此次保留新建 Root，增加后端显式配置的
`delveforge.workspace.existing-roots / DELVEFORGE_WORKSPACE_EXISTING_ROOTS`：
只接纳这些目录中的已有副本，不在那里新建，也不从客户端位置或准备标记推导目录授权。
存储配置需重启后端，新副本只使用主 Root。旧文件、ID、绑定和验证记录不会搬迁或重写。
若旧 Root 未列入允许范围，记录仍可读取，继续操作则按安全检查拒绝。

Root 需规范化为绝对路径，拒绝链接/junction 重定向的已有祖先、与源仓库重叠、不可用目录。
不存在的正常目录通过检查后创建，检查可写性，实际文件创建失败仍拒绝。
直接子目录、净化、revision、准备凭据及不得覆盖已有目标继续检查。
存储问题使用 `WORKSPACE_STORAGE_UNAVAILABLE` 固定错误，不泄漏异常正文，不回退到其他磁盘。
额外复现了未挂载盘符：原寻找已有祖先的循环走到 null，抛出 NullPointerException。
修复前该回归实际失败，补上“没有任何可用祖先”的拒绝后通过；目录不存在与磁盘不存在
不再混为同一种可创建路径。
`GET /api/system/workspace-configuration` 只读显示配置；Prepare 页面可查看和重新读取，
不新增浏览器目录浏览或任意目录写入权限。
配置方法、旧 Root 保留方式及位置核对见 [本地使用说明](../M4_LOCAL_USAGE.md#workingcopy-存储位置)。

实现见 [WorkspaceProperties](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/WorkspaceProperties.java)、
[GitWorkspaceAdapter 存储检查](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitWorkspaceAdapter.java)
及 [只读配置接口](../../backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/system/SystemWorkspaceConfigurationController.java)。

## 回归与手动验收

前端 56 项确定性测试通过，`pnpm typecheck`、`pnpm build` 通过。
[Task E 交互测试](../../frontend/tests/final-ux.test.ts) 使用合成 API 数据，覆盖 702 秒只有一次 POST、
真实 READY/VERIFYING/SUCCEEDED 展示、暂停/离页、断线与未知状态、精确 ID 跳转、旧许可及显式授权。
真实本地 Git 夹具覆盖自定义新 Root、旧副本内容及 revision 不变、越界、已有目标和 Windows junction。
配置测试覆盖两个默认 profile 和环境变量覆盖；API 使用临时 SQLite 与 Mock Provider。
最终 `mvnw.cmd verify`：**PASS，1436 项 / 130 个测试类，失败、错误、跳过均为 0**。
最终运行约 9 分 1 秒，包含真实本地 Git/Maven 集成与 API 回归，JAR 打包通过。

| 模块 | 测试数 | 结果 |
| --- | ---: | --- |
| Domain | 219 | PASS |
| Application | 761 | PASS |
| Infrastructure | 264 | PASS |
| App / API | 192 | PASS |

上述测试不证明任意仓库演化可靠性。

本机复现和测试日志保存在 Git 忽略的 `logs/task-e-*`；持久的可重现证据为提交中的测试源码。
其中 Domain 修复前日志 `task-e-domain-before.log` 保留原失败，最终结果以
`task-e-full-verify.log`、`task-e-frontend.log`、`task-e-typecheck.log`、`task-e-frontend-build.log` 为准。
未挂载磁盘对照保存在 `task-e-missing-drive-before.log` 与 `task-e-missing-drive-after.log`；
前一轮完整验证通过的日志为 `task-e-full-verify-before-drive-guard.log`，不代替最终代码验证。

手动验收建议（不要求再次付费执行）：

1. 注册/编辑时选择 SPDX 或混合许可，尝试 `123`；核对明确授权、原文本和错误提示。
2. 选择缺授权的基础快照，从 Planning 跳转，核对实际 Asset ID、展开/滚动/键盘焦点；
   保存后返回，确认仍为原快照与方向。失败和版本冲突不要显示成保存成功。
3. 查看已存在的成功 Step 的检查与 lastVerifiedRevision。真实长请求的计时、离页及网络中断
   展示仍需在用户主动开展演化时核对；不要为了验收页面自动重复 execute。
4. 后端换 Root 后只读核对配置；在未来用户授权的新 Prepare 中核对 `location`。
   确认旧副本仍在原路径；续用时显式配置旧 Root，不能自动搬迁。

Windows 宿主上 Maven/Git 不是 OS 安全沙箱，不能阻止恶意宿主进程并发篡改文件系统。
只使用受信任、用户授权的仓库和专用存储目录。许可证输入校验不替代法律核对。
现有 GET 不提供源码/补丁历史或后台请求任务状态；长请求断线后的 READY 仍可能未知，
没有添加 M5 恢复能力。M0–M4 保持冻结、M5 暂缓。
