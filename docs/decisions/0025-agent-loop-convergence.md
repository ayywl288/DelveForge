# ADR-0025：Agent 循环收敛与执行事实核验

> 活动完成门槛和自由文本清单已由 [ADR-0026](0026-factual-completion-and-plan-quick-start.md) 替代。下文保留 v7 历史合同。

- 状态：Accepted，2026-11-07，本次用户明确授权。
- 范围：现有单 Agent、Application/Workspace、Docker、冻结 Candidate 与独立自动 Delivery。
- 替代 ADR-0024 的活动 command+cwd 永久义务、scope/kind 注册、失败用例永远必须同名通过及 withheldSourceCount 一票否决。证据 v1–v6 仍按原版本解释，历史失败不追认成功。

## 决定与取舍

外部 Product Direction、RepositoryProfile、Plan、逐 Step 用户确认与可信 revision 不变。用户已授权本任务改变上述机械门槛；原授权、私密资源隔离、源码捕获、冻结差异重放及正式交付完整性不变。没有新增 Verification Plan、业务验收 DSL、固定 check ID、审批、监督 Agent、语言适配框架或编排引擎。

平台能证明具体进程及其源码视图，不能一般性证明任意命令的业务充分性。活动 evidenceVersion=7 改为 Agent 在现有结束 JSON 中引用实际 invocationId。平台从本 Run 自己记录的收据取事实，不接受模型提供的退出码或测试数量替换收据；失败历史完整保留。代价是业务检查是否充分、旧失败是否与本次要求无关，须明确归因于 Agent 并由运行后产品验收评价，不能再由命令等价或测试清单比较冒充证明。

## 工具与工作记忆

- 文件工具与 exec_command 保持。command、相对 directory、可选 timeout_seconds 是活动执行参数；verification_scope/check_kind 退出活动 schema，旧事件仍能展示。
- update_plan 仅接收一段 notes，支持短任务列表、进度、实际发现、阻塞和下一步；最大 4000 字符，低上下文配置至多四分之一上下文。使用完全可选，不修改授权、源码、快照、Candidate 或 Delivery。最新净化笔记留在本次循环并写既有 WORKING_NOTES 事件，压缩后仅注入最新版本。勾选、缺失或过期状态不参与完成判定，也不计源码进展。不恢复旧 Run 对话。
- 系统提示保留稳定原则；任务输入保留 confirmed Step 与产品解释；工具说明承载参数和生命周期；环境块说明执行能力和限制。压缩按最近观察在前保留工具事实与 Agent 归因说明，保留 stdout/stderr 诊断及收据/日志引用，不以 FAILED 排序把旧失败提升为当前失败。压缩只切完整 assistant/tool 组。

## 执行环境与日志

每次 exec_command 仍启动一个非 root 隔离命令容器，/bin/sh 保持原语义。管道或命令序列退出 0 只说明实际 Shell 状态；可用测试报告中的失败与它分开核验，不解析命令推断业务目标。

源码、安装在项目里的依赖及该 Run /m2 缓存跨调用保留。cwd、环境变量、进程和 /tmp 不保留。命令结束、超时或取消后先停止所有容器写入者，再捕获源码。npm cache=/m2/npm-cache，pnpm store=/m2/pnpm-store。工具链/外部服务由 Agent 按实际仓库发现，不因启动沙箱成功就宣称依赖和检查就绪。预检验证现有执行探针及工件代理的资源启动/归属；不理解未知仓库的全部前置条件。

网络仍只有现有固定 Maven/npm 工件主机，保留 internal bridge、代理、凭据隔离、资源限制和取消。没有新增 CDN/私有仓库/宿主服务权限，没有宿主凭据或 Docker socket。已证明在路径校验阶段、未读取或写入的普通拒绝保留 POLICY_REJECTED 类别并返回循环；源码捕获、真实完整性、归属未知或清理失败仍安全停止。

输出先去终端控制序列，再沿现有凭据策略净化，之后才做预览/保留。单流模型预览最多 3000 字符（依 maxOutputBytes 可更小）；进程运输 stdout 最多 256 KiB、stderr 最多 64 KiB。Docker 自身轮转最多 2×4 MiB，极大输出可能只保留轮转窗口。每 Run 的 command-logs 总保留上限 16 MiB（含 JSON/命令/收据）。超限丢弃通过 logDiscarded 明示，不改变命令退出码。

日志只在平台 Run 目录 command-logs/<invocationId>.json，永不挂载项目容器，不污染 Candidate。read_log 按本 Run 调用身份、stdout/stderr/command、字符 offset、limit（默认 4000，最多 8000）翻页；不接受宿主路径。日志及安全 partial.diff/partial-metadata.json 在资源清理后保留；不承诺磁盘损坏或人工删除后可读取。没有保留内容时不伪造引用。单次源码捕获失败前已先存净化命令终态。已有事件保存终止原因和清理状态，无 provider 原始请求/响应体日志。

## 完成、执行视图与 Delivery

现有结束报告：status、summary、validationComplete、executions、limitations。summary/limitations 是 Agent 判断；executions 仅引用具体调用，不创建永久任务要求。必要验证未解决应 status=failed / validationComplete=false；false、缺收据或摘要/笔记单独不能冻结。

活动收据核验：引用存在且属于同 Run，未重复，实际退出 0、无取消/超时、分类成功，前后 snapshot 均等于最终快照，sourceView 与冻结视图一致；可用报告不得含失败。旧失败不被覆盖、改成成功或自动宣布无关。没有通用测试数量门槛、强制改测试文件、同一测试清单或 command+cwd 义务。可修改测试；注解存在不证明真实权限拒绝，属输出质量评价而不是新增生产分析器。

sourceView 记录 FULL/PROJECTED、保留/只读/withheld 数量，executionBaselineTree 保留投影身份。正 withheldSourceCount 本身不是失败。PRESERVED_ONLY 仍不物化、不允许读取、不由模型声明解除；最终 Git 保留原 blob。检查只证明所记录视图，未执行资源的影响未知时须披露，真正缺少输入时按实际失败报告阻塞。

通过事实核验后仍停止写入者、捕获真实源、独立验证受保护变更并重放冻结 diff；既有 Candidate/Delivery 正常自动路径不加人工审批。Delivery 重新核对授权、R/D/B/C、正式工作区与事务边界。未经验证的部分成果不成为 Candidate，不触发 Delivery，不推进 Step/Plan。

## 验证

本任务工程结果、明确未运行项、使用步骤和运行后权限评价见 [收敛验证报告](../validation/agent-loop-convergence-20261107.md)。确定性夹具不证明模型产品能力。付费模型与真实用户项目 E2E 均 NOT RUN。
