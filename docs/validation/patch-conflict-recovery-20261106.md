# 补丁冲突与累计纠正次数修正（2026-11-06）

## 本次真实失败

Run：`5544cf2d-9b0b-4f1a-ab6b-253397525ef3`。完整 API 事件分页至空页，共 138 条，sequence 1085–1222；
只读 SQLite 独立核对。记录为 FAILED / PATCH_REPEATED_CONFLICT，18 次模型回复、42 次工具尝试、
382,926 报告 tokens，无 Candidate，occupied=0、retained=0。以下时间为 UTC-8。

| 时间 | 事件与已证明事实 |
|---|---|
| 07:02:22–07:02:46 | 五次 edit_file 成功，新增迁移、实体、Mapper、请求和响应类型。 |
| 07:02:54 | ResultCode.java 编辑返回 PATCH_CONFLICT / CONTEXT_MISMATCH / mutationNotStarted=true。 |
| 07:02:55 | 同响应预生成的 GlobalExceptionHandler.java 写入被标记 PATCH_NOT_EXECUTED，未执行。 |
| 07:03:02 | 模型重新读取 ResultCode.java，读取成功。 |
| 07:03:08 | 后续编辑被 AgentPatchCorrections 判为相同已失败参数，在调用沙箱前抛出 PATCH_REPEATED_CONFLICT，Run 终止。 |
| 07:03:18 | RESOURCES_RELEASED / CLEANED，资源占用已释放，历史失败保持。 |

因此不是“一次应用失败后模型完全没有调整机会”，而是模型得到反馈并重新读取后，平台用独立的重复补丁规则
提前截断了下一轮尝试。原始编辑正文不持久化，无法从现有记录判定最初原文不匹配究竟是行尾、空白、范围还是内容问题；
也不能证明放开该限制后真实模型必然完成任务。本次没有再次调用付费模型、修改目标仓库或重写失败记录。

上轮审计保留了旧补丁停止策略，旧测试还直接断言重复补丁必须终止，未覆盖重新读取、再次冲突后仍能修正的路径。
因此此前测试通过未证明该策略适合真实模型；本次明确修改这个行为契约，而非仅调整常量或让原断言继续通过。

## 修正与简化

- 删除 AgentPatchCorrections 和 AgentPatchCorrectionException，以及活动元数据中专为此提前拒绝设置的分支。
- 普通补丁冲突不再触发“相同补丁立即终止”或“三次不同冲突终止”。每次实际冲突仍反馈模型，保留已成功修改。
- 复用既有 AgentProgress：三次相同的短工具循环仍判定 NO_PROGRESS；不同编辑/观察继续，真实修改重置记录。
- CodingAgentService 保留冲突恢复的必要前提：mutationNotStarted 必须是布尔 true。缺失、false 或字符串不能当作未写入证明。
- 同轮冲突后的预生成写入继续跳过，逐个返回工具身份；未知修改结果、凭据与路径拒绝、授权、最终证据和 Delivery 校验保持。
- 另两项已复现的累计限制一并修正：响应截断、结束格式纠正只统计连续失败，成功恢复后清零。连续失败仍有停止边界。

没有修改 Docker 工具的实际应用规则、Git 原文匹配或源文件内容，没有新增 Port、依赖、数据库状态或重试框架。
旧事件分类继续保留历史意义；失败 Run 不恢复，修复只影响新启动的 Run。
架构说明和 ADR-0012/0015/0018 已同步明确替代旧的次数决定。

## 验证

在修改生产代码前，新增回归分别复现 PATCH_REPEATED_CONFLICT、PATCH_CORRECTION_LIMIT，
以及已成功工作之间发生的截断/格式错误被累计为 MODEL_PROTOCOL。日志位于未入 Git 的
`target/patch-repeat-diagnosis/red.log`、`response-red.log`。

定向回归 112 项通过（Application 40、CodingAgentService 72），0 失败/错误/跳过。
覆盖重新读取后再次冲突仍可修正、四次不同修正后继续、实际无进展循环停止、未知写入结果停止、
连续协议失败停止、分散错误不累计、同批写入跳过，以及已有凭据保护/取消/完成检查回归。
日志：`target/patch-repeat-diagnosis/targeted.log`。

全量 `mvnw.cmd verify` 于 2026-11-06 20:06（UTC-8）BUILD SUCCESS，1,927 项中 1,867 实际通过、
60 项显式跳过，0 失败/错误；四模块构建与应用打包通过。日志：`target/patch-repeat-diagnosis/backend-verify.log`。
本次未改前端与镜像输入，不重复执行其检查。按用户要求未启动端到端验收或真实付费 Run；
上述应用编排测试使用脚本模型及模拟沙箱端口、真实 SQLite，不能称为真实产品交付证明。

## 使用与剩余边界

用户手动验收前需重启后端，使新编排代码生效；本次无需重建 Agent 镜像。
更多修正机会可能增加调用费用。无进展检测只识别既有的短重复循环，不保证识别所有低质量模型行为；
正常运行仍可能因环境、授权、安全或最终验证失败而停止。未承诺任意仓库成功，也未放宽测试成功定义。
