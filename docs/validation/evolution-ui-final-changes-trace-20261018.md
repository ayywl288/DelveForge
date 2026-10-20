# 验证演化：Step 最终变更与演化轨迹 — 2026-10-18

## Changes

审查基线：main `45acc25635ee1b200315b5b5f24dac1dae6f6965`。本轮未提交 Git、Push 或发布。

- 新增 `GET /api/evolution-plans/{planId}/steps/{stepId}/final-changes`。
  端点只来自服务端 SUCCEEDED Step 的 baseline 与相互匹配的 Execution / Verification Candidate。
  核对副本 / 资产绑定与读取许可，读取期间许可撤销也不能返回文件。
- 独立只读 Workspace Port / Adapter 比较两个固定 Tree 的普通文件 mode / blob 身份，
  返回 ADDED / MODIFIED / DELETED 及数量。重命名为删除加新增，不读源码、不合并 affectedPaths、
  不依赖 HEAD、index、当前工作树或副本最新 revision。
- AVAILABLE / NOT_APPLICABLE / UNAVAILABLE 分开；不可用时 files / counts 为 null。
  只有成功完整比较得到的空列表表示零净变化。客户端路径、Revision 及其它查询参数一律拒绝。
- 页面顺序为当前状态与安全提示 → 最终累计变更 → 演化轨迹 → Verification → 完整历史。
  最近一次 Execution / Repair 明确标注“本轮”及本轮起点。历史成功 Step 按自己的 Verification 展示，
  不要求等于 WorkingCopy 当前 lastVerifiedRevision。
- 轨迹按 Retry 归档轮次及 Repair 快照归属展开，不按时间重排、不重复前驱快照。
  授权只表示授权；执行开始、状态切换等没有保存时间的事实不补造。
  FAILED / RECOVERY_REQUIRED 标签附在实际回滚结果上，不作为带独立时间的伪事件。
- 长文件列表默认折叠，SHA 与路径完整换行；原始统计保留原文，不猜测业务含义。
  完整历史保留既有授权和失败字段；Retry 内的旧 Repair 直接展开，避免再加一层折叠。

Domain、Migration、Recovery、原 Git mutation / 授权操作 / session receipt 逻辑、依赖及其它业务页面未改。
必要只读接口与预算说明已同步 `docs/ARCHITECTURE.md`。

本轮文件清单（20 份，不含原有设计图和忽略的验证产物）：

| 范围 | 文件 |
| --- | --- |
| 后端接口 / 装配 | `StepFinalChangesController.java`、`ExecutionPreparationUseCaseConfiguration.java` |
| Application | `GetStepFinalChangesUseCase.java`、`WorkingCopyTreeDiffPort.java` |
| Infrastructure | `GitWorkingCopyTreeDiffAdapter.java` |
| 后端测试 | `GetStepFinalChangesUseCaseTest.java`、`GitWorkingCopyTreeDiffIntegrationTest.java`、`StepFinalChangesControllerTest.java` |
| 页面与数据契约 | `frontend/src/views/EvolutionView.vue`、`frontend/src/api/client.ts`、`frontend/src/api/types.ts`、`frontend/src/style.css` |
| 展示组件 / 投影 | `frontend/src/components/StepFinalChanges.vue`、`frontend/src/components/StepEvolutionTrace.vue`、`frontend/src/state/stepEvolutionPresentation.ts` |
| 前端测试 | `frontend/tests/step-final-changes.test.ts`、`frontend/tests/step-evolution-presentation.test.ts`、`frontend/tests/final-ux.test.ts` |
| 文档 | `docs/ARCHITECTURE.md`、本报告 |

所有修改文件的完整路径和 SHA-256 见证据根的 `final-integrity.json`。

## Reason

原先 ExecutionResult 保存最近一次执行或 Repair 的真实变化范围，历史授权保存此前失败快照。
两者语义正确，但页面历史在前、最终结果在后，“受影响文件”没有标注范围，容易将本轮文件误认为全 Step。
既有结果不包含完整最终 Tree 文件身份，不能通过拼合各轮路径制造累计净差异。

本次增加调用时的只读 Tree 比较，不改变持久化结果和业务状态，不引入新的历史事件存储。

## Verification

证据根：`logs/evolution-ui-20261018/`。后端在短路径快照 `target/eui18/` 构建与测试，
TEMP / TMP / JVM 临时目录均在其 `tmp/`，未覆盖正常服务 JAR。

### 后端

实际最终命令保存在 `backend-final-read-errors-command.txt`：

```powershell
.\mvnw.cmd -o -pl backend/delveforge-app -am `
  '-Dtest=GetStepFinalChangesUseCaseTest,GitWorkingCopyTreeDiffIntegrationTest,StepFinalChangesControllerTest,CodingProposalValidationTest,UnifiedDiffGitContractTest,ExecuteEvolutionStepUseCaseTest,WorkingCopyInspectionIntegrationTest,WorkspaceCommandRunnerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=-Djava.io.tmpdir=E:/develop/DelveForge/target/eui18/tmp' verify
```

最终原生退出码 0，BUILD SUCCESS，四模块编译打包及 Spring Boot repackage 通过。
8 份 Surefire XML：**99 项通过，0 失败 / 错误 / 跳过**。

| 测试类 | 数量 |
| --- | ---: |
| GetStepFinalChangesUseCaseTest | 7 |
| GitWorkingCopyTreeDiffIntegrationTest | 8 |
| StepFinalChangesControllerTest | 2 |
| ExecuteEvolutionStepUseCaseTest | 29 |
| CodingProposalValidationTest | 14 |
| UnifiedDiffGitContractTest | 26 |
| WorkingCopyInspectionIntegrationTest | 8 |
| WorkspaceCommandRunnerTest | 5 |

真实 Git 夹具覆盖多轮修改 / 修复、撤销、增改删、后续 HEAD 推进、脏工作树、零净变化、
缺失对象、非法 Revision、凭据缺失、alternates、未配置根目录、外部 diff / fsmonitor 配置、
不支持的 gitlink Tree 条目及真实输出预算超限。不可用不返回部分或空文件列表。
关键用例比较查询前后的全仓库文件内容、大小和修改时间，确认只读。

证据：`backend-final-read-errors.log`、`backend-final-read-errors-exit.txt`、`backend-read-errors-reports/`、
`backend-final-results.json`。最终检查将明确目录 I/O 失败归为不可用，并区分 Git 查询失败与对象缺失，
未改变写路径；补正后上述 99 项重新通过。此前 `backend-final-bounded*` 的 99 项及更早 97 项保留，
属于不同轮次，不相加。
本次未重跑此前 1,936 项完整后端回归。

早期两次 Maven 命令因 PowerShell 拆分未加引号的属性而在测试前退出 1，原日志为
`backend-targeted.log`、`backend-targeted-run.log`；改用逐项字符串参数后正常运行。
首轮应用测试有 6 项合成夹具错误（缺少领域要求的 Evidence），未削弱 Domain 校验，补齐合法合成依据。
该轮日志为 `backend-green.log`。失败证据均保留。

### 前端

最后一轮实际执行：`pnpm --dir frontend test`、`pnpm --dir frontend typecheck`、
`pnpm --dir frontend build`。三者原生退出码均为 0。

**14 个测试文件、212 项通过**。新增覆盖多轮 Repair / Retry、Repair 执行失败、用户回滚授权与结果区别、
时间偏移不改变归属顺序、Retry READY 不冒充 Execute、历史成功 Step、真实累计范围与本轮范围区分、
零净变化、不可用、网络中断、版本不匹配、切换 Step 的迟到响应及展开不调用写操作。
原有 receipt / 多标签页 / 授权回归包含在前端完整测试中；未改 `session.ts`。

最终日志和退出码：`frontend-complete*`、`typecheck-complete*`、`build-complete*`。
首次沙箱执行因 Vite 子进程 EPERM 未进入测试；普通本地权限下首轮 210 项中 3 项失败，
其中两项为测试根 props 没有通过父组件响应式更新，另一项为旧测试将 Step 最终版本标为副本 lastVerified。
测试改为真实父组件更新，并按新语义断言 Step 自身版本；未删除原版本或检查结果断言。
原日志保存在 `frontend-test-first.log`、`frontend-test.log`，后续成功日志同样保留。

### 实际后端产物启动

实际 JAR 为 `target/eui18/backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar`，
仅在 127.0.0.1:18086 运行。参数固定新库 `target/eui18/boot-smoke.db` 和
Workspace `target/eui18/boot-managed`，移除 Provider / Spring 外部覆盖变量。
日志确认真实 JDBC URL 位于该快照，空库迁移至 V21。

- connectivity GET：200；projects GET：200 / `[]`。
- 新 final-changes 路由对不存在 Plan：404 / NOT_FOUND。
- 同一路由带 `revision=HEAD`：400 / INVALID_REQUEST，确认实际 Controller 接线及参数拒绝。

日志为 `boot-smoke.log`，响应为 `boot-get-smoke.json`、`boot-reject-client-revision.json`。
该隔离冒烟后端已停止；这不是对真实历史 Plan 的 HTTP 端到端验证。

### 真实 Edge 页面检查

使用现有 Edge、全新 origin `http://127.0.0.1:15376`，加载本轮实际 `frontend/dist`。
只读合成夹具服务器为 `logs/evolution-ui-20261018/preview-server.mjs`，所有非 GET 返回 405，
不连接数据库、真实后端、WorkingCopy 或 Provider。界面合成差异数量不作为真实 Git 计算证据；
真实 Git 比较由上述 Infrastructure 集成测试独立证明。

已实际检查桌面节点与分轮、28 项长路径展开、完整历史展开、本轮标注、历史 Step 自身成功版本、
不可用提示及 RECOVERY_REQUIRED 顶部警告。390×844 视口下检查路径换行和纵向连接；
document clientWidth / scrollWidth 均为 375，无横向溢出，随后恢复正常视口。
浏览器动作只打开合成项目和折叠区域，没有点击 Execute / Repair / Retry。
请求记录 `preview-requests.jsonl` 中没有非 GET；这是实际浏览器检查，不是 jsdom 结果。

截图：

- `edge-final-overview.png`：最终构建，累计差异与多轮轨迹。
- `edge-desktop-trace.png`：桌面轨迹。
- `edge-mobile-files.png`：窄屏长路径展开。
- `edge-mobile-trace-final.png`：窄屏纵向轨迹。
- `edge-unavailable.png`、`edge-blocked.png`：不可用与阻塞警告。
- `edge-final-history.png`、`edge-final-history-ax.txt`：最终构建的完整历史、本轮起点及原始统计。
- `edge-mobile-trace.png` 为首次调整视口后的状态摘要画面，保留原图，不作为轨迹画面引用。

预览已保留供用户查看。若进程停止，在仓库根运行：

```powershell
node --experimental-strip-types .\logs\evolution-ui-20261018\preview-server.mjs
```

正常开发启动仍使用原 `setup-delveforge.ps1`（重新构建前后端），本轮没有替换或重启正常用户服务。
不能用旧后端配新版页面的 404 当作新接口验证结果。

## Risks

- 历史副本 / 所需对象 / 托管凭据或安全前提不满足时，最终差异会明确不可用；不 fetch、不自动恢复、
  不回退到历史路径合集。当前每条 Git 命令 3 秒 / 1 MiB，metadata 与每端 Tree 上限各 10,000 条。
  当前只接受普通 blob 文件条目；gitlink、symlink 或不可无损展示的路径保守返回不可用。
- 没有历史事件流；执行开始、验证开始、独立状态切换时间、未保存的准备错误和 Provider 请求细节无法补还原。
  授权时间与结果时间是保存字段，不等于数据库提交时刻。当前状态节点明确没有记录时间。
- 尚未对真实 Plan / WorkingCopy 调用新接口，未做付费 Provider E2E、真实 Execute / Retry 或完整后端回归。
  Edge 为真实浏览器加合成 GET 夹具；不声称是新 API + 真实业务数据库的一体化验收。
- 原六张未跟踪设计图与历史证据保留；M5 PARTIAL 和 Recovery 延期边界不变。

## Follow-ups

等待用户查看页面和独立审查；暂不提交、Push、发布或创建 Tag，不继续扩展其它页面。
