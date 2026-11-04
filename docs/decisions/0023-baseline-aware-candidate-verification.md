# ADR-0023：逐测试基线差异与带历史债务的 Candidate

日期：2026-11-04。状态：本次用户明确授权的合同修正；实测结果另记。

## 当前问题与原约束

ADR-0022 允许 READY_BASELINE_FAILURE 进入编码，却把所有基线失败变成必须修复的冻结义务。
真实 Run ea9f4a76-daa0-4e9c-ad0b-bab3bbb16ec1 因此在模型声明完成后以 UNRESOLVED_CHECK_FAILURE 结束。
这会把范围明确的演化扩大为历史缺陷修复；但只比较失败总数又可能隐藏新回归。
Candidate 不可变、R/D/B 重放、原资产隔离、Step 确认及正式 Delivery 来源证明仍必须保持。

## 本次决定

使用既有 Application 检查政策，增加证据 v5；不改模块、Aggregate、状态机或数据库表。
v1–v4 继续按原合同解释，不给旧 Run/Candidate 补造证据，不升级旧失败为成功。

系统预检在当前会话独立保留完整 baselineChecks，包括通过检查；成功冻结时一并写入不可变 Candidate 证据。此处不改变失败 Run 的既有材料保留政策。受控 Maven/Surefire、Vitest/Node 报告采集
逐例身份摘要、PASSED/FAILED/ERROR/SKIPPED、失败指纹、测试路径/文件摘要。
未知来源、重复身份、清单/计数不一致或超限不具备基线豁免资格。
报告及清单来自实际隔离检查；模型只得到有界诊断，不能提交或修改验证结论。

v5 比较要求同一固定镜像、配置摘要、运行时/包管理器及已解析依赖身份、执行投影，且最终完整检查
绑定当前源码 snapshot。缺少当前完整结果、测试消失、原已执行测试变为跳过、新失败或失败指纹改变均拒绝。
不能靠减少失败总数、选测、类型检查或编译成功消除全套测试义务。
构建/类型检查仍须在最终 snapshot 重验；测试 runner 未证明或配置变化时保守拒绝差异接受。

只有身份、失败指纹和测试定义均保持的旧失败才可能列为 baselineDebt；测试自身改动或已识别关联源码改动
涉及的失败仍拒绝。关联信息来自失败堆栈及测试中的直接 Java 类型引用；这不是完整依赖分析或覆盖率，
不能证明任意业务影响。受改工程还须有实际执行并通过的新增/修改测试，所有修改测试均须通过。
这验证技术义务，不证明这些测试充分覆盖 Step 的业务要求；交付前差异审阅与独立业务验收仍有意义。

verificationLevel 明确区分 FULLY_TESTED 与 BASELINE_DEBT。前者只表示选定完整技术检查的当前结论，
不承诺无跳过、全业务覆盖或产品就绪。后者保留 TESTS_FAILED、失败/错误计数及逐例债务，绝不改写为 TESTS_PASSED。
status=TESTED/NOT_TESTED 保留既有含义：是否至少有一个当前测试检查通过，不能单独表达整体就绪。
新 Run 的基线已执行测试但清单/运行身份不完整时，预检以 ENVIRONMENT_BLOCKED / EVIDENCE_INCOMPARABLE 停在模型前；冻结也拒绝降级为 v4 接受结果。
这也可能保守阻塞不能关联测试来源的 Node/JUnit 报告。编译先失败、尚未执行测试的路径仍按 v4 要求修复并完整重验，不能获得历史测试失败豁免。

## 完成与交付

模型 completed 仅为请求结束。先只读核对当前检查义务，返回具体缺失工程/新失败/不可比较原因；
模型可补齐真实检查或修复范围内问题。反复提交完全相同的完成声明触发现有三次无进展规则；
不新增累计调用、Token 或总时长上限。finish 仍重新捕获实际文件并独立冻结，早期核对不能替代它。

BASELINE_DEBT 可冻结为 Candidate，但自动 Delivery 和无确认的手动 Delivery 均在正式修改前拒绝，
返回 BASELINE_DEBT_ACK_REQUIRED。用户必须先审阅该 Candidate 的差异及 testEvidence，随后显式请求：

```json
{
  "baselineFailuresAcknowledged": true,
  "expectedDiffSha256": "GET Candidate 返回的 sha256",
  "expectedEvidenceSha256": "GET Candidate 返回的 evidenceSha256"
}
```

请求发送到既有 POST /api/agent-candidates/{id}/delivery。确认审计写入现有持久事件，绑定 Candidate ID、
差异 SHA 和完整证据 SHA；重启后再次核对。Step 确认、费用确认、旧 Candidate 确认都不能替代本次审阅。
没有通用审批框架或新授权表。现有界面不会自动提交这个新确认；显式确认入口目前为该 API。
确认不放宽任何源码、资产授权、原生文件边界、R/D/B/C、CAS 或 SQLite 接受规则。

## 替代方案与代价

继续要求所有旧测试修复会扩大 Step；按总数或模型自报接受会隐藏回归；仅凭用户点击跳过检查也不合适。
这里采用可证明的逐例差异加固定结果审阅。代价是更大的受控内部证据、保守拒绝不稳定/不可比较报告，
以及带债务 Candidate 不能无人确认自动交付。它不是旧失败 Run 的恢复机制；当前没有恢复旧对话或导入
任意保留补丁的产品 API，旧 FAILED 保持失败，新尝试仍需正常启动授权。
