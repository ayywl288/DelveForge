# 验证索引

当前冻结日期：2026-10-28。**构建、确定性测试、真实 Provider 交付、用户端可用性分别记录。**
下列旧报告保持原结论和当时版本，后续成功不会抹去失败。

## 本轮新执行：MVP 文档与发布候选

[机器可读安全摘要](evidence/mvp-freeze-verification.json)：

| 检查 | 新执行结果 | 能证明什么 |
| --- | --- | --- |
| 后端离线 package | PASS，明确 -DskipTests | 当前候选可打包，不是新测试通过 |
| 前端 test | 269/269，19 文件 | 当前前端确定性回归 |
| 前端 build | PASS，含 vue-tsc | 类型与生产构建 |
| 独立启动 | 新 SQLite 至 V26、JAR、Vite 首页与 API 代理通过 | 无 key、Agent disabled 的本地启动链路 |
| 新浏览器 / Provider / Agent 实验 | 未执行 | 不追加新的端到端成功主张 |
| 保护与提交范围 | 见摘要 | main/草稿保留，候选新增内容不含数据库、key 或私有日志 |

后端完整 verify 沿用运行代码 `1b9baa6` 的
[1735 总计 / 1696 执行通过 / 39 跳过](evidence/step4-plan-completion-results.json)。
39 项未执行不算通过；显式 Docker 与模型证据在对应历史报告中单列。

## FinTrack：可展示的核心使用证据

[独立 MVP 审查与真实交付](mvp-fintrack-independent-review.md) 是最新摘要；
[自主稳定化历史](fintrack-autonomous-evolution-final.md) 保留早期失败与修正。

| 事项 | 留存事实 |
| --- | --- |
| Run05 | Candidate `c8f56419-93cd-498c-a127-c0d8fa08a930`，C `5ef8bbcc17273f5b51060e68dd498b53ecbddf16`，3 项最终测试 |
| 展示版 Run07 | Candidate `2751151b-536c-436d-a09c-1cbb93912cf1`，C `44c74a9005ac1d502037d29536373e6920855940`，12 项最终测试 |
| 正式链 | 两次真实 Delivery COMPLETED；各自 P→B/C 关系、diff 与可信 revision 已独立核对 |
| 实际行为 | JWT 注册/登录、匿名拒绝、本人 CSV 与两用户范围、UTF-8/CSV 文本、浏览器按钮下载 |
| 真实下载 | 158 bytes、2 行数据；留存 SHA-256 `346e80d502ca2be31756722a9e05ef21caa6919719148d8e3f54b8bf85a42a49` |
| 限制 | M1–M3 复用；Run07 规划反馈由 Codex 辅助；非完整无人干预旅程，不是任意表格安全保证 |

原始截图、下载、数据库和运行材料保留本机未发布数据目录，入口和摘要见该报告。
当时 FinTrack Plan ACTIVE/WC EVOLVING 是历史事实；本轮不调用其收尾命令或改写历史状态。

## Taskarium：四步技术成功，产品验收失败

[最终阻塞修正与验收](step4-blockers-and-completion.md)、
[Git/SQLite 链路](evidence/step4-final-git-sqlite-proof.json)、
[收尾审计](evidence/step4-plan-completion-proof.json)、
[浏览器失败](evidence/step4-product-browser-acceptance.json)。

| 事项 | 留存事实 |
| --- | --- |
| 原四步 | 4 个 SUCCEEDED、4 个真实 Candidate/Delivery；最终测试 9/19/28/34，均 0 跳过 |
| 最终 Run | `47c23a47-ad06-42fd-b72c-a7a6df9df260` |
| 最终 Candidate / Delivery | `9ab286d5-a76f-48f1-a152-a449a49df944` |
| C4 | `483f5c484fa19df732968ce972cf2fb0f1dd378a` |
| 最终状态 | Plan COMPLETED、WorkingCopy CLOSED，current/lastVerified/HEAD=C4；原仓库 73 文件保持 |
| 实际使用 | C4 启动与匿名 Dashboard 通过；注册/登录均 403，表单未提供已启用 CSRF 所需字段 |
| 漏测原因 | MockMvc 测试显式 .with(csrf())，没有验证实际 HTML 表单提交 |
| 未执行 | 认证后的任务、笔记、日期、进度、浏览器重启保留及第二账户界面隔离 |

截图：[正式交付](evidence/step4-final-delivery-ui.jpg)、[注册 403](evidence/step4-product-register-403.jpg)、
[登录 403](evidence/step4-product-login-403.jpg)。它们是留存截图，不是本轮新拍。
Step 4 收尾任务的六个新增 Run 共报告 375 次模型、579 次工具、10,042,523 tokens；
最终成功 Run 为 131 / 173 / 3,667,978。不是整个项目总成本或供应商账单。
冻结后不生成修复计划，也不让 Codex 代修目标应用来改写验收结论。

## 工程验证入口

| 范围 | 入口 |
| --- | --- |
| Agent / Candidate / Delivery 核心与恢复 | [Task 1](task1-autonomous-agent.md)、[Task 2](task2-autonomous-evolution.md) |
| 仓库预检、JDK、A/P 准备 | [兼容性与准备](repository-compatibility-and-preparation.md) |
| 模型协议与终态 | [协议诊断](model-protocol-diagnostics.md) |
| Maven 准备、正式检查隔离 | [准备修正](maven-preparation-and-activity-fix.md)、[检查隔离](maven-check-isolation.md) |
| Step 4 凭据/补丁/H2/HTML 与 Plan 收尾 | [最终工程报告](step4-blockers-and-completion.md) |
| 旧 M4/M5 与早期 UI | 对应历史报告；已退役路径不作为当前演示合同 |

普通确定性测试不要求付费模型；门控的 Docker、公开仓库和 Provider 检查不是默认都执行。
本次冻结没有重跑历史 FinTrack 或 Taskarium，也没有把夹具事实、定向检查或构建成功扩大为完整产品通过。
