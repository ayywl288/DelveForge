# 换号交接：真实 Run 以 MODEL_INCOMPLETE 结束

> 后续状态：用户已在新账号明确授权继续修复。调查与实现见
> [完成失败诊断修复](completion-failure-diagnostics-20261106.md)。以下保留当时的暂停交接背景。

## 用户要求

2026-11-06，用户明确要求：**先不修，只记录；额度不足，换新账号后再继续修复。**
本次仅只读读取持久化证据并保存交接文件、事件副本和截图；未改代码，未运行测试，未启动模型，未重试 Run，未清理资源或修改产品数据库。
后续接手者先阅读本文件；没有用户的新指令前，不启动修复或付费执行。

## 环境与此前修复

- 仓库：`E:/develop/DelveForge`，当前 main / `5159b15`。
- 上次修复提交 `37d46c9`，本地合并 `5159b15`：取消独立重复补丁/三次冲突终止规则；截断与完成格式只计连续错误。没有推送。
- 上次常规全量验证 1,867 通过、60 跳过，已构建。用户自行运行本次产品验收。
- 相关说明：[补丁恢复修复](patch-conflict-recovery-20261106.md)、[此前工程审计](engineering-reliability-audit-20261105.md)。
- 保留无关未跟踪目录 `frontend/design/`、`grilling/`；不要更改目标仓库或正式 WorkingCopy。

## 本次身份与结果

| 字段 | 值 |
|---|---|
| Run | `0b450d5f-1cd5-47e7-9632-13c14ad3c48f` |
| Plan | `16bdcdb0-5a8b-4185-9e06-66fdb4465bbe` |
| Step | `1b572e75-1856-45a1-b1a2-53037acb0a28` |
| WorkingCopy | `a4b35c2d-4e88-42a9-86c1-6c83e9915d9b` |
| 基线 | `a5dd1681dc3f1897865de28bc64e371d3beec0d3`，MyBookStoreProject |
| 开始/结束 | 2026-11-06 20:10:32 / 20:36:12（UTC-8）；原始 UTC 时间保存在 JSON |
| 状态/原因 | FAILED / MODEL_INCOMPLETE |
| 持久化用量 | 139 次模型回复、172 次工具尝试、10,827,498 报告 tokens；不能按此直接推算费用，含缓存用量 |
| Candidate | 无 |
| occupied / retained | 0 / 0 |

完整持久化事件 640 条，sequence 1223–1862，来自 `data/delveforge.db` 的只读查询。
工具结果：57 次 PATCH_APPLIED、4 次 PATCH_CONFLICT、1 次 INVALID_TOOL_ARGUMENT；
28 次 TESTS_FAILED、4 次 TESTS_PASSED、2 次 COMPILATION_FAILED、1 次 CHECK_MATERIAL_UNAVAILABLE、2 次 BUILD_PASSED_NOT_TESTED。
四次补丁冲突未触发旧的次数终止规则，本次不是 PATCH_REPEATED_CONFLICT。

## 已观察事实

1. sequence 1235：预检 READY_BASELINE_FAILURE。后端基线 3 tests / 0 failures / 3 errors；前端基线 1 test / 1 failure，build READY，typecheck NOT_DECLARED。
2. sequence 1808：曾经请求完成，系统返回 COMPLETION_CHECK_REJECTED / UNRESOLVED_CHECK_FAILURE。
   reason 为 `New, changed or feature-related failure: 7ec56b43a61092c0ff5deb4e9bdc81d77a6705fe4a644aa48ea7f536c05f1135`。
   当时终端显示 instruction 乱码。后续直接核对数据库和 JSON 的 Unicode 内容：两者完全一致，
   均无替换字符，中文指令完整；这是终端输出显示问题，撤回产品指令损坏的怀疑。
3. 拒绝后模型继续编辑并检查。sequence 1852：前端测试 13 executed / 0 failures / 0 errors，TESTS_PASSED。
4. sequence 1856：后端最终记录 17 executed / 0 failures / 3 errors，TESTS_FAILED。
5. sequence 1858：前端 build 为 BUILD_PASSED_NOT_TESTED。以上最后三项共用源码 snapshot
   `efbe2ec200db6259e36f92e05f871fe3ef9190f7a8ef71e45c3acdcbfd2f6456`。
6. sequence 1861：FAILED / MODEL_INCOMPLETE。当前 CodingAgentService 的此分支由无工具调用的结束回复 `status=failed` 触发；
   该状态不能解释模型为什么最终放弃，不能直接归因于 token 余额、重复补丁或平台超时。
7. sequence 1862：RESOURCES_RELEASED / CLEANED，20:36:21（UTC-8）。数据库 occupied=0、retained=0。
   本次未另行枚举 Docker 或磁盘残留，资源结论限于事件及数据库记录。

## 尚未调查，交给下一次任务

- 最终后端 3 errors 是否确为同一基线债务，还是新增/变化/功能相关失败；相同错误数量不能证明身份相同。
- sequence 1808 指向哪个测试，比较了哪些失败指纹、测试定义、环境与功能关联；最终检查后是否仍适用。
- 模型最后 `status=failed` 的具体说明是否仍可从现有材料取得；当前事件只记录分类，不保存完整结束回复。
- 完成检查 instruction 的乱码怀疑已排除，见上文更正。
- 界面“构建通过，未运行测试”是否只是最后一次 build 的摘要，导致掩盖同次 Run 已执行测试的事实。

不要仅凭最后栈位置或失败数量认定根因，也不要直接修改测试、放松基线比较或自动发起新付费 Run。
先恢复现有证据，再按用户新授权决定修复；常规回归需要保留，真实端到端验收由用户手动执行。

## 可携带证据

- [完整 Run 和 640 条事件](evidence/model-incomplete-0b450d5f.json)
- [用户截图副本](evidence/model-incomplete-0b450d5f.png)

这些文件放在仓库目录内，换 Codex 账号后仍可从本地打开。此次未提交或推送，仅保存交接材料。
