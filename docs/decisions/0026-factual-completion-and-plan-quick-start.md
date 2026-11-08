# ADR-0026：Agent 完成判断、执行事实与 Plan Quick Start

- 状态：Accepted，2026-11-08，依据本次 Final Agent Workflow and Product Convergence 的明确用户授权。
- 范围：现有单 Agent Loop、Spring AI、Workspace、Docker、Candidate、独立 Delivery。模块边界和领域状态机不变。
- 替代：ADR-0025 的活动 v7 `validationComplete`、必需成功调用和失败报告否决；ADR-0010 的独立业务验收作为通用自动 Delivery 前置关卡不适用于新 v8 路径。历史验收失败和 v1–v7 证据不追认、不改写。

## 问题、选择与代价

v7 虽然取消了命令字符串永久义务，仍把必要验证完成布尔值、成功退出和报告零失败作为冻结门槛，无法表达“核心工作已完成，但 E2E 环境不可用或有不阻塞当前工作的测试失败”。平台也无法从任意 Shell 或模型说明普遍证明业务正确。

采用 v8 区分 Agent 的完成判断与平台的执行事实。拒绝新建 Verification Plan、业务 DSL、命令白名单或监督 Agent；维持旧门槛不能满足本次明确授权的行为。取舍是模型对完成充分性的判断仍可能错误，交付界面必须保留失败、未知和限制，真实业务验收由使用者独立进行；平台不再将技术执行成功包装成业务认证。

## 完成与证据合同

结束消息为 `status`（completed/failed）、`summary`，可选字符串数组 `limitations` 和调用 ID 数组 `executions`。新报告不要求 `validationComplete`。executions 只支持具体调用 ID，不接受 Agent 提供退出码或重新定义测试计数。

- completed 可以包含失败命令、超时、取消、未知测试详情和未执行 E2E；这些不是自动 Run 否决项。
- failed 保持 MODEL_INCOMPLETE，保留可安全捕获的部分源码，不产生 Candidate。
- v8 checks 保存该 Run 的全部实际命令收据，包括未被 Agent 引用的失败；界面按全部收据计算命令成功、失败、超时、取消和报告计数。跨调用累计报告计数包含重复测试，不声称是唯一用例数。
- 收据身份、同 Run 归属、唯一性、退出码类型/分类、时间、源码身份、变化事实及执行视图必须一致。引用存在、不能重复，且前后源码和视图必须对应冻结源码；过期调用仍可作为历史保留，不能在最终报告中冒充当前检查。
- Shell 退出零与报告中的失败独立保留；未知报告不推断为零测试或通过。平台不解析 Shell 推断哪部分命令是测试，也不判断省略的失败是否无关。
- 冻结前停止写入者、重新捕获实际源码、保护凭据和只读资源、重放 diff 并验证树。Delivery 再核对授权、Candidate/Run 身份、R/D/B/C、原生文件边界与 Git/SQLite 接受事务。
- PROJECTED 视图如实披露；正 withheldSourceCount 本身不否决，秘密仍不物化且原 Git blob 保留，Agent 无权解除访问限制。

v8 必须单独版本化，避免将历史 v7 的失败重新解释为通过。旧 CompletionEvidence v7 条件保持，活动生成写 v8 / FACTUAL_RECORD；容器协议仍为 v9。

## 工作清单、提示与日志

`update_plan` 使用 tasks 数组，每项 title 和 pending/in_progress/completed，最多20项并受4000字符/当前上下文预算限制。它完全可选，只更新当前工作记忆；既有事件可保留审计，但 UI 只显示最新清单，不显示重复的工具与笔记卡片。完成勾选不修改 Step、源码或完成授权。压缩继续保留最新清单、任务原文、最近观察和 stdout/stderr 诊断及日志引用。

系统提示只保留稳定任务、安全和诚实原则；结束格式放在任务输入，编辑和 Shell 选择放在工具说明。小范围修改优先 edit_file；冲突后重读再作一次有依据的重试；批量改写、格式化和生成文件可用 Shell。最终 changedPaths 来自冻结实际 diff。

沿用既有 read_log 和 Run-scoped command-logs：净化后有界保留、按 invocationId/stream/offset 分页，不进入 Candidate。源码、项目内依赖、源卷中的临时文件和 Run /m2 缓存跨调用保留；cwd、环境变量、进程、容器 /tmp 不保留。日志在清理后保留，但受单流和 Run 总容量限制，超额明确标记丢弃。没有新增终端、后台任务管理或语言执行框架。

## Quick Start

Application 的 QuickStartService 使用已有 AiGateway 和只读 WorkspaceReadPort；Infrastructure 的 SqliteQuickStartStore 通过最终 COMPLETED Delivery、全部 Step 的接受记录、Plan COMPLETED、WorkingCopy CLOSED 及可信 revision 核对生成资格。V28 新增 plan_quick_start 表，记录 plan/candidate/revision/status/Markdown/失败原因/时间，不改领域 Aggregate。

CandidateDeliveryService 在交付接受后通知指南服务；正常交付路径先释放正式副本锁，显式补齐 Plan 完成路径在接受后调度异步生成。仅完整 Plan 才发起一次异步文本请求，固定读取最终 commit 的 README、清单、脚本和启动配置，经既有内容能力与净化边界后进入模型。没有新 Agent 或命令执行。32文件、约48K字符输入预算；每份指南最多12K字符。无法读取安全启动材料时显示不可用，不编造材料。

生成失败只更新指南状态，不能撤销交付。GET `/api/evolution-plans/{planId}/quick-start` 只读；POST 同路径显式重试/重新生成。重启将中断生成标为不可用，不自动重新付费。后续同一 flow 的另一 Plan 有正式交付时旧指南标 stale；新 Plan 全部交付后生成自己的指南。未完成新 Plan 可查看旧指南及过期标识。

独立导航「快速启动」和 `#/plans/{planId}/quick-start` 页面展示版本、Markdown 和复制命令。Markdown 只渲染文本节点、标题、段落、列表、代码块、行内强调/代码和 http(s) 链接，不渲染 HTML 或图片。所有生成的启动命令统一标记“尚未执行验证”，不写目标 README。

## 验证边界

本次不调用付费 Provider 或真实目标项目 AgentRun；它们明确为 NOT RUN。确定性测试只能证明上述编排、事实和安全边界，不证明任意仓库或业务任务必然成功。实际执行记录见 [验证报告](../validation/final-agent-workflow-20261108.md)。

## 2026-11-08：启动材料与呈现的有限细化

这次明确授权仅改进材料和呈现，不改变上述完成、授权、交付及独立模型调用合同。

QuickStartSources 仍为 Application 内确定性收集器，不是 RepositoryUnderstanding 或 Scout。通过只读 Workspace
按最终 Delivery commit 列出深度不超过 8 的树，最多尝试 64 个文件、接纳 32 个文件；单文件原文最多 64 KiB，
净化后单段最多 12K 字符、全部材料包含路径标记最多 48K 字符。先完整净化，再按完整行截断，并标注截断。
根 README 优先，接着考虑根 README 的本地文档链接、中文/英文开发安装文档、模块构建清单和 Wrapper、
启动脚本与配置；不递归跟随链接，不读依赖/生成物目录、不联网获取材料。

专用可信原文读取仅用于本收集器，不能据此解释为全局模型披露或沙箱执行权限放宽。路径先按既有排除规则检查，
`.env.example` 是唯一窄例外，只保留规范变量名，所有取值和注释均丢弃。常规文本复用 sanitizeSource 与 sanitizeOutput；
环境示例摘要用有界 StringBuilder，最多 256 个不同变量、12K 字符（含截断标记），在追加前检查限额，避免高密度输入的累计字符串复制。
XML 凭据元素的值先替换。无法保留结构的私钥块、敏感 YAML 多行块及常见命令行口令形式整文件跳过；
二进制/解码替换字符和超限文件跳过。原文不写日志、不持久化、不进入 AiGateway。既有规则仍是已知凭据形式识别，
不引入通用秘密检测器。通用 readModelFile、RepositoryContentPolicy、RepositorySecretPolicy 均不修改。

应用标题由净化后的根 README 一级标题确定；无可靠标题时用原 Software Asset 仓库目录名，不用 WorkingCopy 随机目录或
Plan 功能名。manifest 交叉核对、明确记载/推导命令的区分、缺失内容的单条说明属于单次模型请求的生成约束，
不冒充对自然语言真实性的确定性证明。指南不运行命令；正文最多 6K 字符，连同系统附加标题、真实来源路径与 revision
总计最多 12K 字符。来源保存在原 Markdown 字段末尾，无存储迁移；旧指南仍可阅读，只有显式重新生成才消费模型。
来源区单独作为纯文本 code 节点渲染，不再次解释 Markdown；路径中的反引号、链接或 HTML 标记不能生成链接或元素。

当前性能边界：上述限额约束文件正文读取和模型材料，不约束完整 Tree 元数据枚举。现有 GitWorkspaceAdapter.listEntries
先执行完整递归 ls-tree，再按深度/路径过滤；QuickStartSources 还会排序、建立候选映射。超大仓库的枚举时间及内存仍随
完整树规模增长。本次 Review Fix 只记录此限制，不改 WorkspaceReadPort 或 Git 读取机制。

Run 的开发者详情保留结构化诊断、原始命令、stdout/stderr、分页、保留上限及元数据。最终源码证据按已有 snapshot/afterSnapshot
字段区分，不从命令字符串推断业务活动，不改变 v8 completion。测试报告跨执行计数始终标累计与可能重复；历史失败不变成
唯一当前失败用例数。高级设置保留 targetJdk 自动默认以及兼容的 checkDirectories 请求字段，后者仅用于 Maven 根/JDK 选择，
界面改称 Maven 工程根目录覆盖，不宣称影响 Agent 探索或授权。费用确认保持原入口。
Maven 覆盖仅在当前 RepositoryProfile 技术栈明确包含 Maven 时显示和提交；未知元数据或 Gradle-only 项目不显示该项，
JDK 自动选择和原有手动入口保持。运行历史仅列状态和时间，模型/工具调用数在选中 Run 的开发者详情查看；
切换 Candidate 时，其结果开发者详情重新收起。

## 2026-11-08：最终呈现修正

Run 页面不再提供原始事件/JSON 列表，持久事件及读取 API 保持。预检展示按已有 stage/directory/goal 合并为最新事实，
READY 与 COMPLETED 显示成功，失败及显式取消/中断保留；仅有开始记录时不因 Run 已结束而推断取消，缺失结果显示未记录。
历史命令失败数量仅在开发者详情展示，不等同于最终源码的失败测试数。时间线及历史列表使用页面纵向滚动，
原有 40 条增量展示与回到最新入口保留；只有较长的技术输出保留局部纵向滚动。

独立 Quick Start 请求不解释无关的包名/Compose 名称差异。数据库路径优先遵循根 README/开发指南，并核对实际 Compose、
应用配置及环境示例；宿主机与容器数据库是互斥路径。若材料矛盾，简短说明并分列选项；只有证据支持时才给出服务选择命令，
有相同宿主机端口时明确提示冲突。此处仅调整指南生成约束，不修改 Agent 系统提示、模型调用次数或交付流程。
