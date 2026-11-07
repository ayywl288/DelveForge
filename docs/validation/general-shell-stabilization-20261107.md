# 首次通用 Shell 运行：故障诊断与稳定性修复

日期：2026-11-07。起点：`codex/generic-agent-command-path` / `4d91facd6240781554b54db80b52580e2328f4c7`。
工作分支：`codex/general-shell-stabilization`。本次仅本地提交，不合并、不推送、不重启正式应用。

## 原运行与根因

只读检查运行 `547b8153-d3e9-4161-9f2a-1f35b3e4c487` 的 SQLite 记录和已有部分产物，没有恢复、重试、编辑或删除该运行，也没有修改目标项目。

| 问题 | 实际证据与原因 | 本次修正 |
| --- | --- | --- |
| 成功请求刷屏 | 73 次 MODEL_PROVIDER_DIAGNOSTIC 都是 SUCCESS / attempt=1 / HTTP 200 / responsePresent=true；旧 UI 无条件逐条显示通用标题。 | 主时间线折叠这些常规成功，失败/重试保留；全部诊断在独立折叠区可查，事件持久化和用量不变。 |
| 命令未启动原因不明 | 参数校验合并多个拒绝原因；活动摘要先验证整个调用，拒绝时连安全命令上下文也丢失。timeout schema 未给出运行时上限。 | 固定原因码和恢复说明、实际时限 schema，拒绝也保存净化且有界的命令/cwd；不虚构退出码。 |
| 目录与安装混淆 | 进程本来就在 directory 下执行；提示未充分说明相对路径起点。原运行有 frontend/src 下的 npm ci 退出 1、以及对 `/m2/npm/...` 的错误查找。 | 明确 cwd 和真实缓存位置；缺失目录在启动前明确反馈；不自动改写路径。 |
| 验证字段使用困难 | 原协议已允许普通 npm ci 不带验证字段；不能把所有历史 INVALID_ARGUMENTS 归因于“安装强制要验证元数据”。两字段不配对的错误过于笼统。 | 安装继续允许省略两字段，验证保留成对/范围/类型校验，给出可恢复原因。未增加统一安装流程。 |
| Maven 失败却退出 0 | 原运行两次通过 `... test 2>&1 \| grep ...` 登记后端验证，记录 exitCode=0、executedTests=21、failures=3。/bin/sh 返回管道最后一个命令的状态。 | 指引先保存原状态再读日志；真实进程回归复现并证明可移植修正；界面明确“进程退出成功，测试报告未通过”。 |
| 最终失败含义不清 | 模型返回 status=failed，平台据此记录 MODEL_INCOMPLETE，随后保留部分修改。cancel_requested=0；没有 Candidate。 | 保留模型报告未完成、平台失败、用户取消、provider 失败与完成检查拒绝的区别；展示净化模型说明并注明未独立验证。 |

原运行完成检查确实拒绝了未解决测试失败，保护有效。模型说明中的 JWT_SECRET、MyBatis mock 等归因只是模型陈述，本次未重新运行目标项目，不能据此认定为平台根因或历史债务。旧 INVALID_ARGUMENTS 事件没有保留完整参数/原因，无法追溯每次拒绝的具体字段；本次没有补写历史。

## 改动文件

所有路径相对仓库根目录；除此之外没有纳入本次提交。

- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentToolActivity.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentTools.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/CodingAgentService.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/AgentToolActivityTest.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/AgentToolsProtocolTest.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/CommandCheckEvidenceTest.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/DockerAgentSandbox.java`
- `backend/delveforge-infrastructure/src/main/resources/agent/sandbox.py`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CodingAgentServiceTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CommandUsabilityDockerTest.java`
- `docs/decisions/0024-generic-agent-command-path.md`
- `docs/validation/general-shell-stabilization-20261107.md`
- `frontend/src/components/AgentActivityRow.vue`
- `frontend/src/components/AgentEventDetails.vue`
- `frontend/src/components/AgentTimeline.vue`
- `frontend/src/components/agentEventPresentation.ts`
- `frontend/src/components/agentResultPresentation.ts`
- `frontend/tests/agent-result-presentation.test.ts`
- `frontend/tests/agent-timeline.test.ts`

## 实际验证

全部使用本地确定性模型替身或独立测试夹具，无付费模型请求。两次后端定向 verify 共实际执行 163 项，全部通过；统计只取本次所选测试类的新报告。

| 检查 | 本次结果 |
| --- | --- |
| Maven Wrapper 定向 reactor verify | 122/122，0 失败、0 错误、0 跳过；完整 reactor 构建通过，但不是全量后端测试。 |
| AgentToolsProtocolTest | 5：含普通安装、成对字段、类型/范围/时限和 schema 上限。 |
| AgentToolActivityTest | 39：含拒绝上下文净化、输出限制及原活动分类。 |
| CommandCheckEvidenceTest | 7：含 exitCode=0 + 失败报告拒绝，源码/范围保护。 |
| CodingAgentServiceTest | 69：含参数纠正后继续安装、MODEL_INCOMPLETE、部分产物保留、取消/未知结果等原回归。 |
| CommandUsabilityDockerTest | 1：真实 Docker 根/子目录、重复前缀、缺失目录未启动、无元数据离线 npm 安装、管道退出码 0 与保留原码 7。 |
| DockerAgentSandboxTest#observedFailedTestCannotBeSkippedOrDeletedToFreeze | 1：真实 Maven 失败不能通过跳过/删除测试冻结；真正修复后才接受。 |
| CandidateDeliveryTest | 单独 Maven Wrapper verify：41/41 通过，0 失败、0 错误、0 跳过；真实 Git/SQLite 的交付冲突、未解决失败、恢复与正式副本保护。 |
| 前端全套 pnpm test | 22 文件 / 323 项通过。首次尝试有 2 项失败（文案顺序、用例缺少 scope），修正后全套通过。 |
| 最终前端定向测试 | 最后标点调整和新增结果分类用例后，两文件 93/93 通过；当前总用例 324 项，未声称最终再次全量执行。 |
| 最终 pnpm build | vue-tsc 类型检查与 Vite 生产构建通过；项目无独立 lint 脚本。 |
| Python Linux 脚本回归 | 53 项通过；同批 7 项镜像协议测试需宿主 Docker 而跳过，并在下一项独立执行。 |
| Python 实际镜像协议 | 7/7 通过，镜像 delveforge-agent:shell-stabilization21。 |
| 浏览器 | 独立合成数据预览，使用实际组件/正式容器配色；常规成功折叠、provider 失败可见、输出默认折叠、Enter 展开双流；390px 下无页面横向溢出。临时预览已删除并停止。 |

Windows 初次 Python 全套 60 项中有 2 项因 WinError 1314（符号链接权限）报错，其余 58 项通过；没有修改权限或削弱断言，在实际 Linux 镜像中补验 53 项成功，并独立复跑宿主 7 项镜像协议成功。

日志与截图（本地 target 中，不入 Git）：

- `target/shell-stabilization-backend.log`
- `target/shell-stabilization-delivery.log`
- `target/shell-stabilization-frontend-final.log`
- `target/shell-stabilization-frontend-focused-final.log`
- `target/shell-stabilization-frontend-build-final.log`
- `target/shell-stabilization-python-linux.log`
- `target/shell-stabilization-image-protocol.log`
- `target/shell-stabilization-ui.png`（合成数据，非原运行重试截图）

## 原产物保全

修复前后均只读核对，原运行仍为 FAILED / MODEL_INCOMPLETE，cancel_requested=0、occupied=0、retained=1、candidate_id=NULL。

| 文件 | 字节数 | SHA-256（前后一致） |
| --- | ---: | --- |
| partial.diff | 47403 | f18965944a8920be057721d4c89914c63644206b3c8af57e42ea96f1e47a9393 |
| partial-metadata.json | 1334 | b7b6f95bd4c8ee312ee2fc7e20b40b90362eaf164a8a30fb782cd7410121f97b |

保留 UNVERIFIED_NON_DELIVERABLE 语义，没有创建 Candidate 或 Delivery，没有把原运行改为成功。

## 限制与交付状态

- 原历史缺少参数的拒绝不能补推断；新版 UI 对旧事件明确表示原因未记录。
- 模型是否遵循更清楚的指引，仍须将来另行授权的真实运行验证；本次不调用付费服务。
- 任意 Shell 的业务意义仍由仓库/Step 决定；平台不会自动改写管道或根据说明认定完成。
- 原测试失败和依赖缺失没有在目标项目中修复，也不宣称原任务完成。
- 仅构建独立测试镜像，未替换正在使用的环境。正式采用这些改动需以后按既有启动流程重新构建镜像、启动新版本；本次没有重启正式应用。
- 保留原有未跟踪目录 codex-migration/、frontend/design/、grilling/；没有新增依赖、数据库迁移或架构层。
- 当前分支 codex/general-shell-stabilization；提交完成后以本报告所属 Git 提交为准，不合并、不推送。


## 最终审查修正与集成（7ef63bf 之后）

本节更新上文当时的“不合并”交付状态：用户随后明确授权修正审查问题、验证后本地合并，不推送。

- 当前源码原本已读取 sourceChanged，但 `ok && PATCH_APPLIED || sourceChanged` 让失败写入也清空无进展历史。现按工具区分：edit_file 仍按成功 PATCH_APPLIED；exec_command 必须明确成功、退出 0、未超时/取消且 sourceChanged=true 才清空。未变化、失败、结果缺失不算源码进展。没有新增计数器或重试框架。
- validate_directory 仅在受控响应明确表示 DIRECTORY_NOT_FOUND / mutationNotStarted 时返回可恢复反馈。POLICY_REJECTED 经 rejectPolicy 保留规则及 PATH_VALIDATION 阶段；其他基础设施错误保留原分类，畸形协议报告 TOOL_PROTOCOL。路径遍历、符号链接、保护资源和工作目录限制未放宽。
- 生产改动仅 CodingAgentService.java、DockerAgentSandbox.java；回归涉及 CodingAgentServiceTest、CommandUsabilityDockerTest、DockerAgentSandboxTest、DockerSandboxProtocolTest；本节为唯一文档更新。
- 修复前新增 9 项用例中复现 6 项失败：5 种失败/不完整结果错误清空历史，以及策略路径被当作普通参数反馈。修复后结果见下表。
- 真实 Shell 回归在隔离夹具内交替写入同一文件、形成重复的两个源码快照；连续 6 次写入后能进入第 7 次模型替身响应，没有被 NO_PROGRESS 提前停止。原资产和正式副本均保持原 revision/文件。测试刻意以 MODEL_INCOMPLETE 结束，不生成 Candidate，不冒充业务成功。

| 最终检查 | 结果 |
| --- | --- |
| 后端相关回归及最终 Maven Wrapper verify | 155 项中 154 项通过、1 项旧镜像协议用例因未指定旧镜像跳过，0 最终失败/错误；最终 reactor verify 构建通过。 |
| 前端 pnpm test / pnpm build | 22 文件、324 项通过；类型检查及生产构建通过。 |
| 保留产物 | 两文件 SHA-256 与上表完全一致；未修改 live application data。 |

后端范围：AgentProgressTest 3、AgentToolsProtocolTest 5、AgentToolActivityTest 39、CommandCheckEvidenceTest 7、CodingAgentServiceTest 77、CommandUsabilityDockerTest 2、DockerAgentSandboxTest 定向 3、DockerSandboxProtocolTest 19（含 1 跳过）。当前镜像协议实际运行通过；没有声称执行全量后端测试。首次修复后用例错误地期待 .ssh 为 PROTECTED_CREDENTIAL_PATH，按原规则纠正为 FORBIDDEN_PATH 并新增 id_rsa 的凭据路径检查后，单独重跑 CommandUsabilityDockerTest 两项及完整 reactor verify 均成功；其余通过结果来自同一生产代码的前一次定向运行。

实际日志：`target/shell-review-command-final.log`、`target/shell-review-red.log`、`target/shell-review-verify.log`、`target/shell-review-frontend.log`、`target/shell-review-frontend-build.log`。没有付费模型调用。

合并前实际 ancestry：main=089b26f，generic-agent-command-path=4d91fac，stabilization 的起点为 7ef63bf；main 是稳定性分支祖先，4d91fac 尚不是 main 的祖先。因此合并目标为 main，采用包含 4d91fac → 7ef63bf → 本次小修复的完整稳定性分支，保留所有祖先提交，不仅挑取修复而遗漏通用 Shell 实现。合并后须核对这些提交均可从 main 到达，且 main tree 与验证过的修复分支一致。具体修复/合并提交以 Git 历史和最终回复为准。原有三个未跟踪目录继续保留，不推送。
