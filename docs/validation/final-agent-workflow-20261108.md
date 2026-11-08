# Final Agent Workflow 验证与交接（2026-11-08）

## 范围与状态

- 基线：实际本地 main `e690a9644027e3e96c9f847cf703430b61c92353`；独立分支 `codex/final-agent-workflow`，不 merge、不 push。
- 本文随实现一起提交；最终提交号用 `git log -1 --oneline` 获取，避免在提交内容中自引用提交号。
- 保留用户的 `codex-migration/`、`frontend/design/`、`grilling/`。未改动业务数据库、正式目标 Working Copy、历史失败 Run 或目标项目测试。
- 设计合同见 [ADR-0026](../decisions/0026-factual-completion-and-plan-quick-start.md)。没有引入新 Agent、验证 DSL、命令白名单、语言执行框架、依赖或 Maven 模块。

## 实现

1. `update_plan` 使用可选 tasks 清单，每项 title/status；最新清单替换当前展示，旧事件只保留审计。压缩保留最新清单。勾选不是测试证据，也不能改变 Step 授权。
2. 系统提示缩减为稳定原则；完成格式在任务输入、工具用法在工具描述。当前基线已正确读取 stdout/stderr 和日志引用，本次增加明确回归，没有重复建立日志或记忆系统。
3. 保留普通 Shell、源变化检测、超时/取消和 Run 外部日志。编辑器用于精确局部修改，Shell 可做批量修改；最终文件变化以实际 diff 为准。命令不再制造永久验证义务，这部分基线已有，继续保留。
4. 新证据 v8 / FACTUAL_RECORD 将模型判断与平台事实分开。v1–v7 仍按历史合同读取，不追认旧失败。Run 失败仍不产生 Candidate，安全部分产物仍保留。
5. Run UI 只显示一份当前清单和一份命令，stdout/stderr 用可访问按钮切换，保留日志分页。交付页聚焦结果、实际变更、全部收据汇总、Agent 判断和限制，不再平铺命令历史。
6. Quick Start 是独立 Plan 产物、API 和页面。仅完整 Plan 的全部 Step 正常 Delivery 后生成；已有 AiGateway + Workspace 只读固定最终 commit 的安全启动材料。V28 新增 `plan_quick_start`，不写目标 README。

## 完成与证据合同

```json
{
  "status": "completed",
  "summary": "已实现的具体内容",
  "limitations": ["E2E 环境不可用，未执行；某项检查实际失败及其影响"],
  "executions": ["本 Run 的实际 invocationId"]
}
```

`status` 为 completed/failed，summary 必需；limitations 和 executions 可选。新路径不要求 validationComplete，也不要求所有进程成功或有结构化测试报告。引用必须存在、同 Run、无重复、事实字段一致，且对应当前冻结源码与执行视图。过期收据保留为历史，不能冒充当前检查。

平台保留并汇总全部收据，包括模型没有选择的失败。零退出仅表示 Shell 进程成功；独立的测试报告失败、跳过或未知仍展示。跨调用累计测试数可能包含重复，不声称是唯一用例数。未执行检查只能按 Agent 报告披露，平台不能普遍推断遗漏了哪些测试。

保留的硬边界包括 Step 授权、资产权限、Docker/宿主隔离、秘密与受保护资源、可信运行时收据、源码冻结和 diff 重放、Candidate 身份与一致性、独立 Delivery 的 Git/SQLite 接受和正式副本保护。PROJECTED 视图如实披露，不因 withheldSourceCount 大于零就一概否决，也不给模型解锁秘密的权限。

## 实际验证

以下结果使用临时数据库、临时 Git 仓库、确定性模型替身；不涉及真实产品 AgentRun。

### 后端

最终全模块命令（显式关闭付费及真实目标测试开关）：

```powershell
$env:DELVEFORGE_AGENT_E2E='false'
$env:DELVEFORGE_AGENT_LIVE='false'
$env:DELVEFORGE_REAL_STEP='false'
$env:DELVEFORGE_TASK2_E2E='false'
.\mvnw.cmd -q '-Dspring-boot.repackage.skip=true' verify
```

结果：PASS（退出码0）。190个测试类记录1935项：1883通过、52跳过、0失败、0错误。Domain 209；Application 905；Infrastructure 593（49跳过）；App 228（3跳过）。统计只读取本次运行更新的报告，未混入历史 XML。

该轮全量完成后，真实交付回归进一步发现并修正 Quick Start 根目录参数；之后重跑下述相关 Application/Docker/Git/SQLite 场景并重新构建全部模块，未重复整套长耗时回归。

跳过项为显式开启的 Docker/真实模型/真实仓库用例，以及三项公开仓库新克隆验收；相关 Docker 场景另行执行如下。未将跳过计为通过。

重要覆盖：v8 失败/未知检查不否决 completed；failed 不产生 Candidate；虚构、跨 Run、重复、损坏及过期引用拒绝；v7 旧合同保留；清单协议/压缩；stdout/stderr 及 retained log；SQLite V28、完整 Plan 资格、重启中断状态和旧指南过期；Quick Start 读取最终 commit、授权变化和失败独立性；HTTP 资格拒绝。

普通 `package` 首次因用户正在使用的旧后端 JAR 无法重命名而失败；没有终止用户进程。全回归跳过 repackage，另将当前源码复制到忽略的 `target/final-agent-build/` 后独立打包，验证可执行 JAR，不替换正在运行的文件：

```powershell
.\mvnw.cmd -q -f target/final-agent-build/pom.xml '-Dmaven.test.skip=true' package
```

最终独立打包 PASS（退出码0）；源码与工作区逐文件一致，JAR 包含 Spring Boot 启动器、QuickStartController 和 Application/Infrastructure 模块。没有覆盖用户正在运行的旧 JAR。

### Docker / 实际 Delivery

使用同一源码的独立构建目录，避免与全回归争用输出。执行集：

```powershell
$env:DELVEFORGE_AGENT_DOCKER='true'
$env:DELVEFORGE_AGENT_IMAGE_21='delveforge-agent:generic-v9-verified-cpp'
$env:DELVEFORGE_AGENT_IMAGE_17='delveforge-agent:generic-v9-verified17'
.\mvnw.cmd -q -f target/final-agent-build/pom.xml -pl backend/delveforge-infrastructure -am '-Dtest=GenericCommandDeliveryTest,NativeFrontendDeliverySmokeTest,CommandUsabilityDockerTest,DockerAgentSandboxTest#withheldHighRiskSourceStaysOutsideExecutionWithoutBlanketVeto+finalizationRejectsBinaryDeletionEvenWhenItBypassesTextToolHistory' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

首轮结果：12项中9通过、1失败、2错误，0跳过。问题包括旧镜像目录反馈、最终 Plan 夹具不一致，以及 pnpm6 场景在资源占用释放前尝试交付。根据发现的问题，在独立 `target/final-agent-recheck/` 源码副本中重跑受影响的场景，并补验生成期间授权变化：

```powershell
$env:DELVEFORGE_AGENT_DOCKER='true'
$env:DELVEFORGE_AGENT_IMAGE_21='delveforge-agent:final-workflow-cpp-20261108'
$env:DELVEFORGE_AGENT_IMAGE_17='delveforge-agent:protocol-v9-jdk17-05d760e3e30b9cdf00ef751d99bb44694db1a09bdf87311bfa43d8069ee336c6'
.\mvnw.cmd -q -f target/final-agent-recheck/pom.xml -pl backend/delveforge-infrastructure -am '-Dtest=QuickStartServiceTest,GenericCommandDeliveryTest#cppFailureCorrectionFrozenCandidateAndAutomaticDeliveryPreserveRemainingPlanSteps,CommandUsabilityDockerTest#nestedPathsSetupAndPortablePipelinePreserveActualProcessResults' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

该轮目录反馈和3项 QuickStartServiceTest 通过，真实交付继续揭示下述根目录参数问题。修正后的第一次重跑中，自动 Delivery 在10秒等待上限到达时仍为 COMMIT_READY；测试现等待真实完成（上限60秒）、保留原完成断言并输出事件诊断，指南生成等待上限30秒，没有放宽产品交付条件。

最后定向命令（沿用上述正确镜像环境）：

```powershell
.\mvnw.cmd -q -f target/final-agent-recheck/pom.xml -pl backend/delveforge-infrastructure -am '-Dtest=QuickStartServiceTest,GenericCommandDeliveryTest#cppFailureCorrectionFrozenCandidateAndAutomaticDeliveryPreserveRemainingPlanSteps' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

最终结果：PASS，5项、0失败/错误/跳过；包括真实自动 Delivery 的中间 Step 和最终 Step 两个分支。日志 `agent-convergence-delivery-final.log` 记录实际进入 FIXTURE_GUIDE_FAILURE，证明通过最终文件读取后调用了模型替身，而非在资料读取前失败。

第一次定向重跑发现 Quick Start 将 `.` 传给 Workspace 的根目录读取接口，而端口合同使用空字符串，导致 NO_SOURCES。已修正这一处调用和端口测试；最终重跑必须实际读到交付 commit 中的 README 后再触发模拟模型失败，不能以“指南未生成”代替“模型生成失败”的断言。

GenericCommandDeliveryTest 实际经过 Docker 命令、失败修正、冻结、Git Delivery 和 SQLite 接受。最终 Plan 场景额外执行一个失败的 E2E 不可用命令且不引用它，断言全部收据仍保存、交付完成；随后故意令指南模型失败，断言指南 UNAVAILABLE 而 Plan/Delivery 仍 COMPLETED。非最终 Step 不生成指南。另有截断日志、超时/取消、受保护资源、二进制删除拒绝、原生前端执行和 Shell 管道事实检查。

首轮有两项问题：历史 `generic-v9-verified-cpp` 镜像的工具内容落后于当前源码，导致目录错误反馈用例报错；新增最终 Plan 夹具的数据库没有后续 Step，但内存领域夹具误传 remainingStep=true，造成预期完成与实际 ACTIVE 不一致。已让测试的内存 Plan 与数据库反映相同真实步骤，保持原完成断言；没有修改产品领域规则来通过测试。

已用启动器逐文件校验当前 JDK17/21 镜像；测试 C++ 镜像沿用已有 C++ 工具链、只复制当前可信运行时，独立标签，不覆盖产品镜像。定向重跑采用匹配内容。默认产品镜像仍不含 C++/Go 编译器。

NativeFrontendDeliverySmokeTest 现在在终态后明确等待实际占用释放（最多60秒），断言未释放会失败；不是人工释放或绕过 Delivery 检查。使用当前产品镜像单独重跑四种锁文件场景：

```powershell
$env:DELVEFORGE_AGENT_IMAGE_21='delveforge-agent:protocol-v9-jdk21-05d760e3e30b9cdf00ef751d99bb44694db1a09bdf87311bfa43d8069ee336c6'
.\mvnw.cmd -q -f target/final-agent-build/pom.xml -pl backend/delveforge-infrastructure -am '-Dtest=NativeFrontendDeliverySmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

原生前端最终结果：PASS，4项、0失败/错误/跳过，覆盖 npm、npm lockfile v2、pnpm、pnpm lockfile generation 6。日志 `agent-convergence-native-final.log`，四份实际交付证明分别为独立构建目录中 `target/native-frontend-delivery-{npm,npm2,pnpm,pnpm6}.json`。

上述12个 Docker 场景已分轮通过；重跑范围针对发现的问题，没有把首轮失败记录改成通过。另有3项 QuickStartServiceTest 最终通过。相关测试全部使用模型替身。

### 前端与浏览器

```powershell
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend build
```

- 23 个测试文件、333 项测试全部通过；类型检查与生产构建通过。项目没有 lint script，未虚构运行。
- Quick Start Markdown 使用 Vue 文本节点安全渲染，测试 HTML/脚本协议不可执行、复制按钮、只在完成后允许生成、过期及失败展示。
- 按本轮用户反馈，复制操作改为28px双框图标按钮，悬停或键盘聚焦显示 Copy，复制成功显示勾号并在1.8秒后恢复。删除单独占行的复制工具栏。导航以独立「交付成果」分组、绿色浅底与播放图标区分五个工作流阶段。
- 全部收据汇总测试包含未引用失败、零退出但报告失败、无报告详情。Run 清单更新和命令不重复有组件回归。
- 使用合成接口数据的真实 Chrome 页面，在 1440 / 768 / 390 宽度检查 Quick Start 和 Run；无横向溢出、无控制台错误，工作清单和选中命令均各一份。截图逐一检查。不是产品 E2E。
- 浏览器结果与截图保存在本次 Codex 可视化目录 `C:\Users\28226\.codex\visualizations\2026\11\08\01a1ba40-528a-7443-9d32-821552b4d2fc\`；测试日志为根目录忽略的 `agent-convergence-*.log`。

## Quick Start 生命周期与局限

- 路由：`#/plans/{planId}/quick-start`；GET/POST `/api/evolution-plans/{planId}/quick-start` 分别读取和显式生成。
- 仅在 Plan COMPLETED、Working Copy CLOSED、全部 Step 的接受 Delivery 与可信最终 revision 一致且资产仍允许读取/使用时生成。普通中间 Step 不触发模型。
- 状态为 GENERATING / AVAILABLE / UNAVAILABLE。生成失败只更新指南状态，不撤销代码交付；重启把未完成生成标为不可用，不自动再付费，可在页面重试。
- 同 flow 后续 Plan 有新交付时旧指南 stale，新 Plan 全部交付后生成自己的版本。
- 读取材料有边界：最多32份、约48K字符、单文件32KiB、单份最多读取到输入12K字符，目录枚举深度5。过大或被安全策略排除的文件可能不进入材料；模型须标未知，不能保证任意仓库都有完整启动资料。
- 生成指南不执行启动命令，页面统一标记未执行验证。Markdown 支持标题、段落、列表、代码块、行内强调/代码和 http(s) 链接；不渲染任意 HTML 或图片。
- 第一次和重新生成都使用单次独立 AiGateway 文本请求，不是启动 Coding Agent。重新生成只再次整理同一最终 Delivery 的资料，不启动容器或执行代码。
- 付费模型：**NOT RUN**。真实目标项目 AgentRun / 产品 E2E：**NOT RUN**。确定性夹具不能证明任意仓库或业务需求必然成功。

## 用户构建与重启

1. 在原前后端终端按 Ctrl+C 停止旧服务，释放被占用的后端 JAR。本次未替用户停止服务。
2. JDK21、Git、项目要求的 Node 和 pnpm12.9.1、Docker Linux Engine 就绪后，在 PowerShell 执行：

```powershell
Set-Location E:\develop\DelveForge
git switch codex/final-agent-workflow
.\setup-delveforge.ps1
```

启动器构建后端、安装前端并校验当前镜像；按其提示配置密钥，不把密钥放进仓库。启动会正常应用 V28，本文测试没有对业务数据库执行迁移。访问 `http://127.0.0.1:5173`，后端默认8080。若仅需单独构建，在停止旧后端后运行：

```powershell
.\mvnw.cmd '-DskipTests' package
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend build
```

## 手工真实验收（本轮未执行）

1. 用户准备独立、可丢弃的演示项目与业务数据，自行启用模型。记录原仓库与正式副本基线，创建并审阅 Plan/Step 后明确确认当前 Step。
2. 观察 Agent 先读仓库，使用原生命令与编辑方式；复杂任务可出现当前清单，多次更新不得重复卡片或越权完成别的 Step。查看命令的 stdout/stderr 与完整保留日志。
3. 针对一次真实受限环境，让 Agent 如实报告失败或未执行检查；核对交付页保留所有失败和未知，不把零退出当测试通过。若 Agent 自判 failed，应保留安全部分产物且没有 Candidate，不人为改成成功。
4. completed 时核对冻结 diff、源码版本、Candidate 与独立 Delivery；原始 Software Asset 保持不变。中间 Step 交付后不应生成 Quick Start。
5. 逐 Step 正常交付到 Plan 完成后，打开「快速启动」；确认最终 Delivery/revision、配置占位、真实脚本和访问方式。复制命令，在用户的演示环境实际执行并验收业务功能。
6. 可在演示环境测试指南调用失败：Delivery/Plan 应保持完成，页面可稍后重试；后续 Plan 改变交付项目后旧指南标过期。真实业务与启动结果单独记录，不把本报告的夹具通过当作验收结论。
