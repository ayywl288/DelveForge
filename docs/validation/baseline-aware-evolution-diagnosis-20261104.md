# 失败真实演化诊断与基线感知完成

日期：2026-11-04，Asia/Shanghai。仅本地修改，不合并、不推送。本轮 **0 次付费模型调用**。

## 结论

原 Run 的完整后端失败均为旧失败，没有证据表明它新增了 Maven 回归；**但保留功能仍未满足原 Step，不能直接交付**。
三个独立问题必须分开：

1. DelveForge 的旧合同允许带基线失败编码，却要求交付前修复全部旧失败；完成声明之前又没有反馈检查义务。
2. 上轮应用包嵌套了旧 Infrastructure JAR，未包含最后的公开标题修正。独立适配器探针与实际付费 Run 的执行投影不同。
3. Agent 的 19 文件实现已具备预检端到端骨架，但真实提交没有共用求值器，日历有迟到结果覆盖，失败提交后未使预检失效。

已实现版本化逐例比较、完成前反馈和带债务 Candidate 的显式交付确认，以及增量打包修正。
旧 FAILED Run 没有受支持的 resume/重新冻结入口；本次没有制造 Candidate、Delivery 或新接受 revision。
未来基线感知 Candidate 是合适的，前提是新 Run 实际产生可比较证据并满足当前功能；这不等于追认这份保留实现。

## 固定身份与材料

仍为 Techd81/Campus-laboratory-equipment-reservation-system-based-on-Spring-Boot-Vue，
原 commit `307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05`，tree `8f05690251b35365a5a5d60a5ddcd5d99bc38ec2`。
Plan `7bc1be8e-d40a-4df4-9c45-fb83fe97c932`，Step `adb5edec-22a3-426f-bcbd-8ecf2563938d`，
WorkingCopy `c9086c3b-f5d7-4ec7-89dc-fd7f5218d794`，Run `ea9f4a76-daa0-4e9c-ad0b-bab3bbb16ec1`。
不重抽仓库、不重做画像/方向/计划，不改第三方源或正式副本。
只读核对原 Step 的持久 scope，明确包含“与现有预约创建规则共享的校验评估能力”；下方共享求值缺口不是临时加给 Agent 的新要求。

从已停止的原 Agent 容器只读导出实际隔离源码，原卷保持只读使用；离线 Maven 在新的受限临时容器中执行。
对照树从固定原 commit 的相同执行投影构造，使用同一 JDK17 运行镜像及原 Maven 缓存的只读副本。
旧运行的 7 个保留资源不注入容器。另设独立对照试验恢复当前政策允许的两个只读公开 env，二者不混算。
测试报告来自实际执行；逐例按 class#method 比较，失败正文只去除 Java 堆栈行号后取 SHA-256。
没有修改测试来凑结果，没有向原 Run 注入新检查或写其数据库状态。

## 基线/最终逐例比较

实际持久预检是 **270 执行、5 failure、32 error、0 skip**，并非前一报告引用的独立探针 5/31。
同旧投影重新执行的基线完全复现 5/32；保留最终源复现 **280 执行、3 failure、31 error、0 skip**。
34 个最终失败的身份、分类与归一化指纹均相同；3 个旧失败解决，新增 10 个测试均通过，原测试没有消失。
完整逐例表如下，不能用总数减少代替该比较：

| 测试身份（类名#方法） | 基线 | 最终 | 逐例结论 |
| --- | --- | --- | --- |
| `controller.NotificationControllerTest#getNotifications_customPagination` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#getNotifications_defaultParams` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#getNotifications_success` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#getNotifications_userIsolation` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#getNotifications_withFilters` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#getUnreadCount_success` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#getUnreadCount_zero` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#markAllAsRead_success` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#markAllAsRead_userIsolation` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationControllerTest#markAsRead_success` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#expiredToken_returns401` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#invalidToken_returns401` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#noToken_returns401` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#noToken_unreadCount_returns401` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#refreshToken_returns401` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#validToken_activeUser_returns200` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#validToken_activeUser_unreadCount_returns200` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#validToken_disabledUser_returns403WithCode1002` | ERROR | ERROR | 旧失败，指纹相同 |
| `controller.NotificationSecurityIntegrationTest#validToken_pendingUser_returns403WithCode1005` | ERROR | ERROR | 旧失败，指纹相同 |
| `resource.BootstrapAssetsTest#bootstrapAssetsIncludeUserReviewRecordTableSetup` | ERROR | ERROR | 旧失败，指纹相同 |
| `resource.BootstrapAssetsTest#developmentFrontendApiBaseUsesViteProxy` | ERROR | ERROR | 旧失败，指纹相同 |
| `resource.BootstrapAssetsTest#startScriptForwardsDynamicFrontendOriginToBackendCorsConfig` | ERROR | ERROR | 旧失败，指纹相同 |
| `resource.BootstrapAssetsTest#startScriptSelectsFrontendPortDynamically` | ERROR | ERROR | 旧失败，指纹相同 |
| `resource.FrontendDevProxyAssetsTest#developmentFrontendUsesRelativeApiBaseUrlAndViteProxy` | ERROR | ERROR | 旧失败，指纹相同 |
| `service.impl.DashboardServiceImplTest#getStatistics_categoriesSortedDescending` | FAILED | FAILED | 旧失败，指纹相同 |
| `service.impl.DashboardServiceImplTest#getStatistics_emptyDatabase` | ERROR | ERROR | 旧失败，指纹相同 |
| `service.impl.DashboardServiceImplTest#getStatistics_nullCategoryFiltered` | FAILED | FAILED | 旧失败，指纹相同 |
| `service.impl.DashboardServiceImplTest#getStatistics_nullCounts` | ERROR | ERROR | 旧失败，指纹相同 |
| `service.impl.DashboardServiceImplTest#getStatistics_success` | FAILED | FAILED | 旧失败，指纹相同 |
| `service.impl.DashboardServiceImplTest#getStatistics_trendDateFormat` | ERROR | ERROR | 旧失败，指纹相同 |
| `service.impl.ReservationServiceImplTest#getReservationList_success` | FAILED | PASSED | 已解决 |
| `service.impl.ReservationServiceImplTest#getReservation_success` | FAILED | PASSED | 已解决 |
| `service.impl.ReservationServiceImplTest#updateReservation_success` | ERROR | PASSED | 已解决 |
| `service.impl.ReviewServiceImplTest#getPendingReviews_success` | ERROR | ERROR | 旧失败，指纹相同 |
| `service.impl.ReviewServiceImplTest#getReviewedList_success` | ERROR | ERROR | 旧失败，指纹相同 |
| `service.impl.SseServiceImplTest#broadcast_removesOnError` | ERROR | ERROR | 旧失败，指纹相同 |
| `service.impl.SseServiceImplTest#subscribe_addsToList` | ERROR | ERROR | 旧失败，指纹相同 |

### 失败分类与责任

- NotificationControllerTest 的 10 项、NotificationSecurityIntegrationTest 的 9 项：现存测试切片未提供构造器所需的 NotificationSseService，ApplicationContext 无法启动。目标库既有测试配置问题；不是本轮预检 API 导致。
- DashboardServiceImplTest：3 项旧断言失败、3 项旧 Mockito 多余 stub；ReviewServiceImplTest 2 项多余 stub。实际调用/返回与旧测试期待不符，保留为目标库历史债务，不在本 Step 中顺手修复。
- SseServiceImplTest 2 项：把 ConcurrentHashMap.KeySetView 转成 List，目标库既有测试/实现约定不一致。
- BootstrapAssetsTest 4 项：固定 commit 缺少 start.ps1，其中一项在旧投影先因 env 缺失失败；文件未曾存在于该 commit，不能由模型凭空补建启动脚本以消除失败。
- FrontendDevProxyAssetsTest 1 项：旧运行投影未物化原仓库已有的公开 env。它是 DelveForge 执行材料/运行包问题，不能列为上游本来缺配置。
- ReservationServiceImplTest 的 3 项解决：两个旧 selectById stub 调整为实际使用的 selectBatchIds；一个多余 stub 移除。原断言仍保留，没有删除相关测试。

当前公开资源政策的单独复测为基线 5/31、最终 3/30：FrontendDevProxyAssetsTest 恢复通过。
BootstrapAssetsTest 的一个失败从 env 缺失变为 start.ps1 缺失，故不能把不同投影的失败总数拿来证明同环境无回归。
测试需要的主机私有配置仍不进入容器；并未开放真实数据库、凭据或外部服务来“修”测试。

Vue/Vitest 的新实际复测：基线 **387**、最终 **398** 项均通过，逐例清单完整，原测试身份无缺失，新增 11 项。
同一保留源码这次实际运行了 `vue-tsc --noEmit && vite build` 并通过；它是新取得的构建证据，不能倒填成旧 Run 最后 snapshot 的构建。
这些诊断容器使用只读输入与原冻结依赖，无模型、无网络，没有把新结果写入旧 Run。

## 19 个 Agent 改动的评估

以下路径均是目标工程内部路径；审阅的是完整变更，不把新测试通过当作实现充分性。

| 路径 | 评估 |
| --- | --- |
| backend/src/main/java/com/lab/reservation/controller/ReservationController.java | 新 POST /reservations/precheck，从认证上下文取用户，不接受任意 userId；基本接线具备。 |
| backend/src/main/java/com/lab/reservation/dto/ReservationDTO.java | 新 PrecheckRequest，设备/日期/起止时间基本校验；没有扩大创建 DTO。 |
| backend/src/main/java/com/lab/reservation/service/ReservationService.java | 增加类型化预检契约。 |
| backend/src/main/java/com/lab/reservation/service/impl/ReservationServiceImpl.java | 增加逐项结果、冲突时间、匹配规则；但 evaluateAllRules 仅在预检调用，createReservation 仍复制旧判断。“双方共用”注释不符合代码。 |
| backend/src/main/java/com/lab/reservation/vo/ReservationPrecheckVO.java | 通过状态、规则键/解释/错误码、冲突范围等响应模型具备。 |
| backend/src/test/java/com/lab/reservation/controller/ReservationControllerTest.java | 新合法请求和无效 DTO 两项；服务被 mock，不证明创建/预检规则相同。 |
| backend/src/test/java/com/lab/reservation/service/impl/ReservationServiceImplTest.java | 新 8 项涵盖通过、信用/数量/时长/提前天数、冲突、关闭/不可用、无效时间；修复 3 个旧夹具问题；未验证创建与预检的共享/一致性。 |
| frontend/src/api/modules/reservation.ts | 新类型化预检请求。 |
| frontend/src/api/paths.ts | 新 endpoint 与后端一致。 |
| frontend/src/api/types/reservation.ts | 规则、冲突和总结果类型与 VO 对应。 |
| frontend/src/components/reservation/ReservationPrecheckPanel.vue | 逐项、失败解释、冲突范围及错误重试展示具备；状态正确性依赖调用方。 |
| frontend/src/components/reservation/__tests__/ReservationPrecheckPanel.test.ts | 新 4 项验证展示/错误/重试；属于组件夹具验证。 |
| frontend/src/composables/__tests__/useReservation.test.ts | 新 2 项验证返回预检数据及错误透传；创建失败仍被现有 submit 吞为 void 返回。 |
| frontend/src/composables/useReservation.ts | 接入预检；沿用 submit 的捕获异常/返回 void，调用方无法据返回值区分创建成功与失败。 |
| frontend/src/constants/reservation.ts | 新中文规则说明、失败/重检提示；文案存在不代表对应状态失效已实现。 |
| frontend/src/views/reservation/ReservationCalendarView.vue | 可显示预检并在改选时清空结果；缺少请求身份绑定，迟到响应会写回旧结果。 |
| frontend/src/views/reservation/ReservationFormView.vue | 预检未通过时阻止创建；创建被拒绝后只显示 staleHint，仍保留 passed，下一次提交跳过重检。 |
| frontend/src/views/reservation/__tests__/ReservationCalendarView.test.ts | 新 2 项覆盖同步返回/事后清空，未覆盖请求尚未返回时改选。 |
| frontend/src/views/reservation/__tests__/ReservationFormView.test.ts | 新 3 项覆盖预检失败、成功创建调用及预检网络异常；未覆盖创建接口失败后的第二次提交。 |

### 独立确定性功能反例

在无网络容器中，读取保留的 Vue SFC/实际 composable，使用原 TypeScript/Vue 依赖执行脚本；
仅控制 API 返回的时机和结果，不改变目标源、不生成“成功”测试报告：

1. 日历先请求 09:00，再改选 14:00，最后返回旧请求：实际 selected=14:00、shown=09:00、passed=true。
2. 表单第一次预检通过，实际 composable 的创建 API 连续拒绝两次：creates=2、prechecks=1，预检仍 passed=true，仅出现两次 staleHint。

这是脚本/状态级复现，不是浏览器或完整数据库业务验收。它足以说明相关新增单测有遗漏；
未手工修补目标实现，也未把这些诊断结果导入原 Run。原创建端仍有服务端判断，不能把 UI 问题扩大称为已证实的授权绕过。

## 已确认的 DelveForge 根因与修改

### 基线债务被错误扩大为实现义务

旧 AgentCheckEvidence 把 baselineFailures 与后续检查按顺序加入同一未解决集合，只允许最终完整 TESTS_PASSED 清除。
该实现符合当时 ADR-0022，但不适合范围明确的既有工程演化；不是报告计数丢失，也不是选测 46 项足以替代全套。
当前版本显式修订该合同，见 [ADR-0023](../decisions/0023-baseline-aware-candidate-verification.md)。

新增 BaselineCheckComparison，采集完整逐例身份、结果、测试源摘要、失败指纹及运行环境。
完整当前检查与基线的镜像、配置、运行时依赖/包管理器和执行投影须相同；未解释的缺失、由执行变跳过、
新失败、改变的失败、修改测试仍失败或识别到与修改源码关联的失败均拒绝。
修改工程须有实际通过的修改测试。构建/类型检查也须当前 snapshot；全量失败不能靠降低总数或选测清除。

证据 v5 明确 FULLY_TESTED / BASELINE_DEBT；债务仍是 TESTS_FAILED，保留真实计数和逐例列表。
既有 v1–v4 按旧合同解释。新 Run 的基线已经执行测试但清单/环境不足时，在模型前 ENVIRONMENT_BLOCKED / EVIDENCE_INCOMPARABLE；冻结也禁止降级到旧格式绕过逐例证明。
尚未执行测试的编译失败路径保留原有严格修复/重验，不能宣称完成基线测试差异证明。没有数据库迁移或把旧证据升级为成功。
关联依据是堆栈和测试直接类型引用，不是完整依赖/覆盖分析；有意义的功能测试与独立验收仍不可替代。

带债务 Candidate 可冻结，但自动/无确认交付均以 BASELINE_DEBT_ACK_REQUIRED 阻塞。
用户审阅固定 Candidate 差异和证据后，经既有 delivery POST 显式提交确认及两个 SHA；
确认写入现有审计事件，重启后仍绑定同一 Candidate，不复用费用/Step 确认。此显式确认目前通过 API，未新增 UI 审批流程。
正常 Git 重放、原生不覆盖边界、源码保护、资产授权、Candidate 不可变、CAS 和 SQLite 接受规则保持。

### 模型 completed 缺少可操作的义务反馈

CodingAgentService 原来只解析 status=completed 后立即返回循环，再由 finish 拒绝。
实际事件显示最后做了 46 项选测，然后模型声明完成；没有保留完整模型正文，不能声称知道模型内部为何作该判断。
系统让这个声明进入 FINALIZING，却没有先反馈完整检查仍失败，这是已确认的编排缺口。

现在完成前只读核对当前检查，向模型反馈具体工程/新失败/证据不可比较原因；修复后仍独立 finish。
反复相同完成声明受原三次无进展规则约束，没有新增累计模型调用、Token 或总时长上限。
系统提示也明确不为无关旧债务扩大已确认 Step。

新 v5 结果若仍有历史债务，前端结果摘要明确显示“保留历史测试失败”和交付前审阅要求，
不再借最后一次前端或选测通过显示整体绿色通过；保留逐项检查的真实结果。
这是现有呈现器及其测试的有限修改，没有新审批界面。

### 实际包没有包含最后源码

旧应用包 SHA-256：`47f5673641fe1517bc78c3656f07c904061686d5b8eb6ddf4355957dc8b95136`。
嵌套 Infrastructure SHA-256：`ec0b46816d1968e9e19ed1885261076bcbb78f62d129360bcdb97a6c62b18bbe`。
已直接检查嵌套 RepositoryContentPolicy.class：不存在 `_TITLE` 规则，而同目录单独构建的 Infrastructure JAR 存在该规则。
这证明增量 Spring Boot 再打包沿用了旧模块，并解释实际付费 Run 的 env 屏蔽和与探针不同的 32 errors。

根 POM 设置 maven.jar.forceCreation=true；本轮实际包再逐个比较嵌套模块与 reactor JAR 的 SHA，
并检查公开标题、v5 比较器及完成指令均在包中。不能只凭 package 日志为 BUILD SUCCESS 就断言运行了最新实现。
后续原项目运行镜像继承当前工具并保留原公开 UPLOAD_PATH 临时目录配置，已核验摘要及工具版本；没有启动模型进程。

## 验证

| 验证 | 实际结果 |
| --- | --- |
| `backend-final-verify.log` | PASS；总数 1894，跳过 56，failure/error 0/0 |
| `docker-final.log` | PASS；总数 91，跳过 0，failure/error 0/0 |
| `baseline-docker-final.log` | PASS；总数 9，跳过 0，failure/error 0/0 |
| `selected-preflight-final.log` | PASS；总数 1，跳过 0，failure/error 0/0 |

完整后端共 1,894 项，其中 56 项跳过，实际执行 1,838 项，failure/error 均为零。
91 项定向回归与最终 9 项 Docker 检查是独立命令的执行数，可能相互覆盖，不能累加成唯一测试数。
9 项命令包括 8 个完整基线场景和固定原目标的诊断探针。该诊断探针曾以 ENVIRONMENT_BLOCKED / Docker unexpected EOF 结束，
测试允许记录阻塞后成功返回，因此命令通过不能代表它已就绪；这次中断材料单独保留。
随后单独增加预期 outcome 断言复测原目标，须实际返回 READY_BASELINE_FAILURE 才通过，Provider 请求为零。
最终应用包中三个嵌套模块逐字节等于本次 reactor 产物，五个 Agent 资源也逐字节等于当前源文件。

此前一次 Docker 检查因 Windows 主机内存不足失败：Docker CLI 报无法创建 OS 线程（errno=1455），
该命令为 9 项、1 failure、2 errors，失败日志及报告保留，不能算通过。
仅降低验证 JVM 堆和主机 CLI 并发后完成上述 9 项复测；容器的 2GiB/2CPU/PID/无网络限制均未放宽，未终止用户应用或更改系统设置。

完整后端使用 Maven Wrapper；实际执行数为总数减跳过数，外部 Provider/Docker 门控跳过不报告为已执行。
最终 Docker 回归含干净基线、旧失败保留、新失败、删测/跳过、配置不可比较、功能失败，以及真实 Maven/Node 与源保护。
既有 Git/SQLite Delivery、安全/完整性回归与新显式审阅、重启审计、HTTP 确认合同均验证。
合成夹具不是这次第三方产品的 Candidate 或 Delivery。原目标的全量 Maven、387/398 Vitest 和最终构建事实见上文。
DelveForge 前端此次仅修改结果呈现器：22 文件 / 310 项测试通过，既有 typecheck/build 脚本通过，机械检测无发现。
实际组件以明确标注的合成数据在正常和 390px 窄屏预览，历史失败文案可见且换行；这不是随机目标的业务验收，临时预览已停止。

机器可读材料见[结构化诊断证据](baseline-aware-evolution-diagnosis-evidence-20261104.json)。
原始逐例报告、镜像/包身份和两个反例输出留在本地 `target/baseline-aware-20261104/`，不提交源码或模型对话。

## 恢复与真实交付路径

原 Run 仍 FAILED/UNRESOLVED_CHECK_FAILURE、occupied=0、retained=true，Candidate/Delivery 都为零。
原仓库和正式 WorkingCopy 各 360 文件、index、HEAD/tree/status 与本轮前逐字节/摘要相同。
原 100 次模型调用、6,299,421 Token 保持历史事实；本轮所有诊断和合成回归新增 Provider 调用为零。

现有 Delivery/重试只消费成功冻结的 Candidate。该失败 Run 没有 Candidate，现场没有存活会话，
requireSession 及 SQL freeze 的 FINALIZING/占用条件也不允许把 FAILED 直接重新冻结。
没有受支持的“挂回保留卷/继续旧对话/导入补丁”API；本次未另造一个，也未直接改 SQL 状态。

因此：**要获得真实交付，仍需要新的正式 Agent Run，但当前不应仅为原样重试再花一次费用。**
新技术基础是已验证的基线差异、完成反馈与实际包一致性；原 Step 的共享后端规则和两个前端失败场景仍须真正实现并测试。
后续沿同一原 revision、Plan、Step、正式副本，经正常费用确认启动新 Run，仅由 Agent 在新隔离源修改。
全量同环境逐例检查与当前功能检查合格后冻结；如有历史债务，用户先审阅差异/债务并显式确认，再走正常确定性 Delivery。
最后绑定实际接受 revision，独立验证预检/创建一致性、冲突变化、迟到响应、失败重检和用户操作。
当前结果是“工程修正与确定性诊断”，不是第三方产品交付成功；无需重新选择仓库或替 Agent 手写实现。
