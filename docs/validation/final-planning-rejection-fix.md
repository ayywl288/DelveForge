# Final Fix：偶发规划拒绝的定位与诊断修复

日期：2026-10-11。基于 `ee15646`，分支 `codex/final-planning-rejection-fix`。
本次只修复规划失败分类、版本预检与前端请求边界；不调用真实 Provider，不操作用户数据库、
演示仓库或 WorkingCopy。M0–M4 保持冻结，M5 暂缓，Task G 尚未开始。

## 1. 这次历史失败能确认到哪一步

只读检查 `E:\develop\DelveForge\logs\m4-local\delveforge.log`：

- 第 902 行，2026-10-11 20:16:00.454：同一线程的 Gateway 记录
  `phase=COMPLETE code=PROVIDER_RESPONSE_COMPLETE httpStatus=200 elapsedMs=32988`。
- 第 903–906 行，20:16:00.462：`/api/evolution-plans/planning` 随后抛出
  `EvolutionPlanningRejectedException → EvolutionPlanningService.java:64 → GenerateEvolutionPlanUseCase.java:70`。
- 基线 `ee15646` 的第 64 行只对应以下成员校验：

```java
if (!profile.capabilities().containsAll(current.capabilities())
        || !profile.modules().containsAll(current.modules())
        || !profile.limitations().containsAll(current.limitations())) {
    throw new EvolutionPlanningRejectedException("Current State facts must come from the Base Profile");
}
```

结论：这条保存的失败是 **Provider 已返回后，提案的现状事实未通过 Domain 校验**。
至少一个 capability、module 或 limitation 不是本次 Base Profile 的原文成员。
JSON 结构解析、Evidence 引用还原及返回后的资格检查已完成，才会到此处；
不是“LLM 没收到对象”，也不是本条堆栈所能支持的 Round 归属拒绝或 HTTP 传输失败。
当前前端源码不存在“LLM 未收到对象”这一字面提示；历史统一映射及前端提示只表达笼统 AI 失败。

**不能确认的部分：**原始 Planning Proposal、当时请求的三类对象 ID / Context 版本没有保存在
这些日志里。无法还原哪个事实区、哪一条文本被改写或新增；无法证明后一次请求与前一次
使用完全相同的 ID，也无法证明“重新加载”是后一次成功的原因。不能把模型自述当证据。
日志中的运行堆栈与基线源码位置一致；没有留存可绑定该失败请求的执行器制品 SHA。

## 2. 实际调用、拒绝条件与副作用

```text
Controller 转换三个依据 ID + 可选 project(contextId, expectedVersion)
 → 服务端读取 Direction / Asset / RepositoryProfile
 → 检查明确的流程归属、Context 版本、未绑定 Plan、分析与资产关联
 → Domain 检查 SELECTED / Candidate Asset / Profile 属于 Asset / analyzedRevision / 授权
 → 一次 Gateway 调用，输入为选中 Direction、Base Profile 和调用内 Evidence Catalog
 → 严格 Parser → Evidence Resolver
 → 再读取可变资格并核查 Context、Direction 状态、授权版本
 → Domain 接受提案
 → 一个事务保存 Plan、Step、内容、Evidence 和 Context.planId
```

Parser 拒绝非单个 JSON 对象、缺失/错误类型字段、不完整 Step、已移除的 preconditions、
无效 VerificationCriterion 等。Resolver 拒绝未知 Evidence 引用，不新造依据。
Domain 还会拒绝缺失提案、改变 TargetState、现状事实不来自 Base Profile、复用能力不来自
capabilities/reusableAssets、依据不属于输入、缺少 Base Profile 依据，以及缺少 Step/依据。
三者是不同阶段，不根据同一异常 message 的自由文本来分类。

本条第 64 行拒绝发生在创建 Plan/Step 身份和保存之前：不会留下半份 Plan，也不会把新
Round 绑定到错误 Plan。用户此前显式创建的新 Round 是独立成功操作，规划失败不会删除它。
提交阶段如果发生授权或 Context CAS 冲突，SQLite 事务回滚整个 Plan 与关联。
这些语义由临时数据库集成测试核对；本次没有读取或修补用户 SQLite 来强制恢复状态。

## 3. 为什么不修改 Prompt 或放宽校验

实际 `EvolutionPlanningExtraction` 已发送 Base Profile 的 capabilities/modules/limitations。
既有指令明确要求现状条目、复用能力和目标意图逐字来自指定输入。Domain 用的 Profile
也是本次发送的同一份快照，不由浏览器缓存直接提供事实内容。

对相同合法 ID，重新加载不会改变 `containsAll` 规则；再次提交会进行一次新的模型调用，
可能产生合规的不同提案。这是合理解释，**不是对历史第二次成功原因的证明**。如果刷新
同时换了分析 ID、方向或版本，那是另一个输入，当前日志无法对照。没有证据支持现在
修改提示、自动替换事实、增加模型重试或放宽 Evidence/TargetState/CurrentState 校验。

## 4. 最小修复及错误契约

| 失败阶段 | HTTP / API code | 含义及安全操作 |
| --- | --- | --- |
| PROVIDER_CALL | 502 `PLANNING_AI_FAILED` | Gateway 调用或 Provider envelope 处理失败；不是一律断言网络超时 |
| PROPOSAL_PARSE | 502 `PLANNING_PROPOSAL_INVALID` | 模型结果未满足规划结构契约 |
| EVIDENCE_RESOLVE | 502 `PLANNING_EVIDENCE_INVALID` | 引用了本次 Catalog 不存在的依据 |
| DOMAIN_ACCEPTANCE：现状三类事实 | 502 `PLANNING_CURRENT_STATE_INVALID` | 事实成员校验拒绝 |
| DOMAIN_ACCEPTANCE：其它接受规则 | 502 `PLANNING_REJECTED` | 产品意图、复用能力、依据等拒绝 |
| INPUT_SERIALIZATION | 500 `INTERNAL_ERROR` | 本地输入组装失败，不解释为模型不合规 |
| Context 版本/已绑定/删除状态冲突 | 409 `PROJECT_CONTEXT_CONFLICT` | 重新读取明确的项目，不能把它当模型拒绝重提 |
| 明确归属不一致 | 409 `EVOLUTION_FLOW_SCOPE_CONFLICT` | 保留只读历史，不按路径或画像推断归属 |

前五类明确失败发生在保存前，页面说明“未保存计划，可明确重新生成，可能再次计费”，
不会自动重试。其它既有授权错误仍保留各自 code，基础状态冲突仍由原有 409 守卫处理。
日志只新增固定 phase/code/reason：三个现状区分别使用
`CURRENT_CAPABILITIES_NOT_FROM_BASE_PROFILE`、`CURRENT_MODULES_NOT_FROM_BASE_PROFILE`、
`CURRENT_LIMITATIONS_NOT_FROM_BASE_PROFILE`。不记录不匹配原文、对象内容、Prompt、模型响应、
源码、异常 message/cause message；遵守 ADR-0002。详细 HTTP 观测仍由已有 Gateway Adapter 提供。

另有两个局部正确性收紧，不能冒充历史第 64 行失败的根因：

1. Planning Scope 先单独校验当前未绑定 Context 的版本，再校验 Direction 归属；避免把过期
   Context 一概归为归属不明。AI 返回后再次预检，最终提交 CAS/事务仍保留。
2. 前端固定本次 Direction、Asset、Profile、Context 及页面代次，丢弃销毁或离开上下文后的
   迟到规划结果。切换项目/方向清空基础分析选择，不能沿用旧表单残留。计划已经返回但
   关联回读失败时保留 Plan ID、阻止再次生成，要求恢复核对；连接断开或未预期 5xx 也先
   查询。明确的规划拒绝与“结果未知”不采用同一重试提示。没有增加自动付费调用。

没有数据库迁移、索引变更或终态恢复。没有修改 M1 Repository Understanding、Planning
Prompt、CodingProposal、Git Mutation、Verification 或 M5。

## 5. 回归与执行证据

首次红测试：临时库的现状拒绝本应返回 `PLANNING_CURRENT_STATE_INVALID`，旧代码返回
`EXTERNAL_CAPABILITY_UNAVAILABLE`；见 `logs/final-planning-red.log:461`。未删断言。

- 聚焦后端：67 项通过（Domain 13、Application 24、App 30），
  `logs/final-planning-focused-final.log`。包括三个事实区拒绝、Parser/Resolver 阶段、
  九种 Domain 原因、日志中的敏感异常文本不泄漏。
- SQLite/Mock Provider：同项目 A→Plan A→B→新 Round→Plan B、两个项目独立保存、
  过期版本/失效方向/错误 Base 在 Provider 前拒绝、调用期间版本变化拒绝、
  失败 Round 无 Plan/版本变化、重读后相同输入成功，旧请求不能再生成第二份 Plan。
- 前端：85 项通过，`logs/final-planning-frontend-final.log`；直接重提/重读后 DTO 一致，
  无自动重试，已保存后的回读失败、断线、迟到响应、新会话与基础表单隔离。
- `pnpm typecheck` 和 `pnpm build` 通过；证据为 `logs/final-planning-typecheck.log`、
  `logs/final-planning-build.log`。
- 最终 `mvnw.cmd verify`：1474 项通过（Domain 220、Application 761、Infrastructure 275、
  App 218），0 failure/error/skipped，耗时 9 分 9 秒，打包完成；
  `logs/final-planning-verify.log`。本次新增测试不是历史真实 Provider PASS 的替代证据。

测试使用确定性 Gateway 替身与独立临时数据库。完整 verify 中的 Git/Maven 集成夹具仅操作
测试目录，不是用户的 Evolution Step 或新的真实 Provider Smoke。

## 6. 手动验收与剩余限制

1. 使用本次打包版本重启后端、重载 Vue；打开原项目 A，保留旧 SUPERSEDED Plan 的历史。
2. 明确选中 B、建立新规划轮次、明确选 Base 分析。只有点击生成才调用 Provider。
3. 成功后检查新 Plan 绑定于新 Context；旧 WorkingCopy 与验证 revision 不被迁移。
4. 如果提案被拒绝，核对固定 API code 及服务端 phase/reason；可以主动再次生成，刷新本身
   不产生 AI 调用，也不能保证下一份模型输出合规。
5. 断线或“计划已保存、回读失败”时先从工作台重开同一项目，查看已保存 Plan；不要重复生成。
   查询暂时显示未绑定也不证明此前断开的服务器请求已结束。现有 CAS 保证最多一个 Plan
   绑定，但未新增规划执行租约，不能保证用户跨标签主动重提不会产生额外 Provider 消耗。

下一次定位所需的最小证据是：执行器版本、失败时间、固定 code/phase/reason、浏览器发送的
三个 ID 及 contextId/expectedVersion、随后只读 Project/Plan 状态。无需公开画像、源码、
Prompt 或模型响应。要确认历史具体改写文本，仅靠现有结构日志无法恢复，本轮不增加原文留存。

本次证明错误分类和流程边界的确定性回归；**不证明模型每次规划都成功**。原 SUPERSEDED
Plan 不恢复为 ACTIVE，原始未保存模型提案不可恢复。完成后停止业务扩展，等待手动验收。
