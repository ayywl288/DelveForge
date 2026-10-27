# 修复后沿原四步 Plan 的续跑验收

日期：2026-10-27（Asia/Shanghai）。分支 `codex/maven-check-isolation-resume`，起点 `2c291e5`。
Phase A 修复与确定性证据提交：`d8c6cc5`。未合并、未推送。
完整本机日志、原始 API 响应、事件、失败 XML、一致 SQLite 备份：
`E:/develop/DelveForge/data/maven-check-resumed-20261027/`。

## 结论

**完整四步验收未通过：3/4 Step 正式交付，Step 4 因 `CHECK_MUTATED_SOURCE` 停止。**
本轮是修复后的新验证，不改变旧独立实验的失败结论。没有原样重跑 Step 4、改写计划、
豁免变化文件或手工修目标应用。最新正式且可信版本为 C3；没有 C4，也没有完成最终应用用户验收。

## Phase A：复核与无模型修复

实际复核 `2c291e5`：在线依赖准备确已使用一次性工程副本和只读原输入，前端也确已统一过滤
`apply_patch` / `edit_file` 的普通重复明细。但正式 `run_checks` 仍直接执行于可写 Agent 源卷。

在原始 Taskarium 提交 `91f69ab1d7ab3a654814a1971cd3871ab0dffb1d` 上，正式离线测试实际执行一项且通过，
随后因 `database/taskarium_db.mv.db` 内容变化而拒绝；mode 均为 `100644`，无其他源码/配置变化。
路径、前后哈希与快照在 [Phase A 调查](maven-check-isolation.md)及其修复前后 JSON 中。

`d8c6cc5` 将每次正式检查移到无网络 `check-source` 副本，权威 Agent 源只读挂载；
返回 snapshot 须匹配复制前完整输入，权威源检查后也须保持不变。仅已有、头部标识符合 H2 MVStore 的
`.mv.db` 内容变化可在副本中接受，并逐项保存 `runtimeChanges` 的路径、类别、前后 mode/hash。
其他非生成物新增、删除、模式或内容变化仍拒绝；副本不进入 Candidate。测试报告清理后读取，
仍绑定精确输入 snapshot，冻结仍判断 stale。没有依赖、Module、Domain、持久化结构或交付合同改变。

| 新执行验证 | 结果 |
|---|---|
| 修复前真实 Docker adapter | 1 项以 `CHECK_MUTATED_SOURCE` 失败，先确认红测 |
| 修复后真实 Docker adapter | 3 项全部通过，零跳过；含原 H2、权威源只读/保持及真实源码违规拒绝 |
| Python 工具测试 | 42 项，7 项镜像门控跳过，实际执行 35 项，零失败/错误 |
| 后端完整 `mvnw.cmd -o verify` | 166 份 XML、1,702 项，36 项门控跳过，实际 1,666 项，零失败/错误；打包成功 |
| 前端 | 本轮无前端源码修改；256 项、typecheck/build 是 `2c291e5` 的保留证据，不是本轮新全量执行 |

只有上述门控成功后才启动新后端和付费 Agent。Phase A 模型调用为 0。

## Phase B：原始对象、授权与实际交付

复用原 User Profile、Repository Profile、SELECTED Direction、Plan 和 WorkingCopy，没有重新发现或规划。
Plan：`281e9daa-e68c-48f1-8a8a-89326dffca29`；WorkingCopy：`8ab00aed-43c3-4a57-aadc-2d3391cb6618`。
四个 Step 的 ID、goal、scope、plannedChanges 和 criteria 保持原值；执行前后不可变定义 SHA-256 均为
`a06ae807115fe6024ae5abe58bc8d18786603c5df9fef3e01044ac551f5e9f77`。
原始规划快照文件 SHA-256 仍为 `bc23239993340200a87c740c5bad64e3d8f8a83db0696348ec0780dbb59125a1`。

Step 1 保留此前 READY 确认，正常创建新 Run；后续逐个经普通 confirm/start API，
均提交 `paidUsageAcknowledged=true`，只有前一步正式 Delivery 核对成功后才启动下一步。
每步 targetJdk=17，没有新增累计 tokens、调用次数或总时长停止配额。

当前 Delivery 以 candidateId 为主键，所以下表 Candidate/Delivery 共用同一身份，并非另有未展示的 Delivery UUID。

| Step | 新 Run ID | Candidate / Delivery 主键 | 正式 revision / 结果 |
|---|---|---|---|
| 1 | `72e7ff85-31e3-4283-a4f7-0fc8cc9359de` | `18677cfa-58f6-4d30-81a2-35d90c57fded` | `6eb7c59f656be4c9b9813bb31ad19bf1e644f206`，完整 9 项测试通过 |
| 2 | `7198fab3-c773-47d5-80db-156b60aa12ee` | `1d2b25a9-fb25-4d58-8349-124f36085822` | `ed3bc1c4bcdf6959c94446586086552b730d967c`，完整 19 项通过 |
| 3 | `7eceebcf-6d9f-4b37-afbe-aa39085709d2` | `7a7c5067-d98d-4f31-9f9a-729feb97c5c3` | `6cad3bc93ca88e8cdf3a19d3a470e036d084d086`，完整 28 项通过 |
| 4 | `e9f883f1-6210-4c72-b52b-38d9932089ff` | 无 | Run=FAILED / POLICY_REJECTED，Step 仍 READY，未交付 |

各步测试数量是当步完整 suite，包含前步回归，不能相加冒充不同测试。
新增生命周期测试实际检查创建/编辑持久化、必填与日期校验、用户归属、页面字段和完成状态；
后续首页分类、周安排与进度测试随累计源码通过。它们不替代最终浏览器业务验收。

正式 Git 链为：

```text
A=P  91f69ab1d7ab3a654814a1971cd3871ab0dffb1d
C1   6eb7c59f656be4c9b9813bb31ad19bf1e644f206  parent=A
C2   ed3bc1c4bcdf6959c94446586086552b730d967c  parent=C1
C3   6cad3bc93ca88e8cdf3a19d3a470e036d084d086  parent=C2
```

[完整实际证明与用量](evidence/post-fix-resumed-proof.json)逐步核对了 Run/Candidate/Delivery baseline、
Git parent/tree、Diff SHA-256、SQLite Step revision、WorkingCopy current/lastVerified 和干净状态。
C3 的完整源身份还独立从 Git blob 重算，与 Candidate 和精确归档的 snapshot 完全相同：
`91502c1f16f6e50f962427c3b3c55a09d9bad02aeeb7a99ae64bab5b3aa8a492`。

## Step 4 的新阻塞与边界

Step 4 首次编译成功，新增重启持久化测试后曾测试编译失败；DeepSeek 自行修改后再次运行 test。
sequence 930 明确记录 `POLICY_REJECTED / CHECK_MUTATED_SOURCE / TOOL_EXECUTION`、
`ok=false`、`resultRecorded=false`，随后 sequence 931 Run=FAILED。不能把此次未接受的运行解释成测试通过。

这是新的源码完整性拒绝。持久化事件没有保存被改动文件的前后清单，Run 的 `retained=false`，
容器、源码卷和本地沙箱目录已由产品清理。**具体路径目前不可恢复；不能确定一定是 H2、新运行期文件，
还是实际源码/配置变化。** Phase A 仅证明原始基线的已识别 H2 情形，未覆盖这里的全部副作用。

依照本轮停止边界保留失败，不继续付费试错或扩展实现，也不通过忽略文件解决未知变化。
前三步的 Candidate/Delivery 保持，WorkingCopy 仍干净且 currentRevision=lastVerifiedRevision=C3。

## 浏览器与启动检查

真实 DelveForge 页面刷新后显示 [3/4 正式交付](evidence/post-fix-three-deliveries.jpg)，
[Step 4 拒绝规则、失败与无 Candidate](evidence/post-fix-step4-failure.jpg)与 API/SQLite 一致。
这些是 DelveForge 状态展示证据，不是目标应用的可用性证据。

曾对 C3 做一次有限的隔离离线打包尝试。初次 Windows Git 导出受换行转换影响，被入口完整快照检查
在 Maven 前拒绝；只读导出参数修正后，归档/Git/Candidate 三者身份相同，未修改目标源码。
随后 `mvn -o -DskipTests package` 在解析 `maven-jar-plugin:3.3.0` 时因离线缓存缺失而失败，应用未启动。
此处 skipTests 仅用于打包，不计为新测试证据；实际测试证据仍是同源 C3 Candidate 的 28 项全量结果。
[源码绑定与启动边界](evidence/post-fix-source-binding.json)记录上述实际结果。

没有继续搭建下载环境或修目标 POM。目标应用没有启动，注册登录、日期/周安排、进度、重启保留、
第二账号隔离等最终用户流程未执行；因此不能宣称原模拟用户需求已实质满足。
仅本轮启动夹具的两个容器及四个卷在归属核对后清理；日志、归档和验证元数据保留。

## 用量、干预与历史保护

| 新 Run | 模型调用 | 工具调用 | Provider 报告 tokens |
|---|---:|---:|---:|
| Step 1 | 93 | 133 | 2,534,918 |
| Step 2 | 37 | 47 | 924,002 |
| Step 3 | 32 | 44 | 830,300 |
| Step 4 | 26 | 44 | 513,338 |
| 本次续跑合计 | **188** | **268** | **4,802,558** |

数字为报告用量，不是金额或完整账单；未再次加算 reasoning tokens。没有新的 M1–M3 调用。
旧独立实验的 741,731 tokens 保留在旧报告中，不混成本次用量。原始新 Run 事件分别保存在
[Step 1](evidence/post-fix-step-01-events.jsonl)、[Step 2](evidence/post-fix-step-02-events.jsonl)、
[Step 3](evidence/post-fix-step-03-events.jsonl)、[Step 4](evidence/post-fix-step-04-events.jsonl)。

Codex 只修复 DelveForge 的 Phase A 边界、配置隔离运行环境、通过正常 API 确认/启动原 Step、
只读核对证据和执行上述有限检查。Taskarium 业务/测试代码均由 DeepSeek 自行修改与修正，
没有 Codex 业务代码、实现提示、追加要求或指导重规划。失败后没有额外 Run 重试。

原仓库 HEAD 与全部 73 文件 size/SHA-256 保持不变，Git 干净。旧失败 Run 仍为
`a650c901-e265-4ab6-80e0-05339296e2c2 / FAILED / DEPENDENCY_PREPARATION_MUTATED_SOURCE`，
30 次模型、65 次工具、635,067 tokens、无 Candidate，旧报告/证据未改写。
SQLite integrity=ok、外键问题=0、活跃 Run/Delivery 预约均为 0；执行前后各有一致备份。

**建议：暂不原样重跑 Step 4。** 下一次付费尝试前，先取得拒绝时有界的相对路径、变化类别和前后哈希，
再无模型区分合法运行期数据与真实完整性违规。本轮到此停止，不将这个缺口扩展成无界修复项目。
