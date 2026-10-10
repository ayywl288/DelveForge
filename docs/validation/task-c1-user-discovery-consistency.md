# Task C1 — 同版本审阅一致性与会话展示

2026-10-11；基线 `4aeba6d`。上一轮报告实际位于
[validation](user-discovery-conversation-continuation-review-fix.md)，并非 retrospectives。
本次只涉及 M1 及对应 Vue 展示，不调用真实 Provider，不修改 M2–M4、Workspace 或 Verification。

## 实际原因与修改

旧 `AssessProfileSufficiencyUseCase` 每次进入审阅都重新评估候选；普通对话的充分结论
只返回浏览器，没有服务端记录。同版两次随机评估可能相反，源码允许这种情况；没有保存
用户失败轮次的原始响应，本次不猜测其具体模型输出。此外，评估期间版本/状态变化没有
在写入时原子检查，旧候选可能覆盖后来的状态。此前审阅组件替换聊天区，并与侧栏重复。

现在只保存每份画像最近通过最低充分性检查的版本（迁移 V13）。记录只含 ID + revision，
依赖既有不可用不同内容覆盖的六区/Evidence 快照，不保存问题、原始响应或评估历史。
只能由 Application 校验通过的结果写入；浏览器 sufficient 标志不能授权审阅。

- 普通对话的内容保存与通过记录在同一事务完成。失败不留下半轮更新。
- 只读问题成功时可以记住通过结果，但不新增 Profile 内容、Evidence、revision 或状态。
- 同版显式审阅复用记录；没有记录才评估一次。无论充分与否，审阅响应 nextQuestion 均为空。
  不足只反馈 missingAreas，下一次提问仍需要明确用户动作。
- 内容或 Evidence 变化后事务内删除旧记录；状态转换不推进内容版本，不能单凭状态推断充分。
- `saveIfUnchanged` 先用条件 UPDATE 获得 SQLite 写锁，再检查原始版本、状态及内容快照，
  最后同事务保存。审阅、确认、Continue/Reopen、纠正和对话都使用该保存边界。
  AI 调用在事务外；并发状态/版本变化返回冲突，不把旧结果用到新状态上。
- 纠正后的 REVIEWING 仍保持原有语义：用户重新核对并明确确认新版本。旧充分记录失效，
  不冒充新版本评估；确认自身的版本与状态检查仍由 Domain 执行。

新增只读 `GET /api/user-profiles/{id}/review-readiness`：

```json
{"profileId":"<实际 ID>","revision":7,"sufficient":true}
```

没有有效通过记录时 sufficient 为 null（未知）。刷新只使用版本匹配的真实结果显示
非阻塞提示，不发起模型调用、不恢复未持久化的聊天。审阅 POST 支持 `{revision:7}`；
旧无体 API 调用兼容，Vue 总是提交实际版本。以上为契约示例，不是用户原始数据。

前端第一次收到本页会话的真实充分结果时显示一次选择弹窗。继续探索使用已有下一问，
不重新调用模型；“审阅并确认画像”仅进入审阅，最终确认仍须核对当前版本并勾选。
后续充分轮不重复弹窗，刷新只显示提示。“本次对话 / 画像审阅”两个视图保留本页聊天，
审阅时侧栏不重复大段六区和依据；返回探索恢复对话。桌面聊天可见高度提高，独立滚动与
输入区布局保留；长依据可折叠，并在打开后独立滚动。

## 调用次数与验证

| 操作 | Gateway 次数 |
|---|---:|
| 普通业务 Discovery Turn | 2，未增加 |
| 常见附和 / 流程输入 | 1，沿用前轮处理 |
| 继续深入且已有当前问题 | 0 |
| 主动按需提问 / Continue 或 Reopen 后提问 | 1 |
| 同版有效充分记录后的显式审阅 | 0，原为 1 |
| 无有效记录的显式审阅 | 1 |
| Confirm / 页面刷新 / GET | 0 |

确定性测试覆盖同版复用、内容/Evidence 更新失效、过期 revision、同版状态竞态、伪造内容
快照拒绝、Provider 失败原子性、审阅不插入问题、合法 Continue/Reopen/Confirm、一次性弹窗、
刷新恢复提示及聊天视图切换。实际 SQLite/Flyway 与 HTTP 测试使用独立测试数据库。

| 检查 | 实际结果 | 本机证据 |
|---|---|---|
| M1 聚焦后端测试 | 339 项通过 | `logs/task-c1-backend.log` |
| Maven Wrapper `verify` | BUILD SUCCESS；1401 项，失败/错误/跳过均 0，耗时 9 分 39 秒 | `logs/task-c1-verify.log`、`backend/*/target/surefire-reports/TEST-*.xml` |
| `pnpm test` | 四个文件、34 项通过 | `logs/task-c1-frontend.log` |
| `pnpm typecheck`、`pnpm build` | 均通过 | `logs/task-c1-typecheck.log`、`logs/task-c1-build.log` |

旧测试对“审阅不足返回新问题”及“依据默认全部展开”的断言已随明确的产品契约调整，
新增回归验证不插入问题、折叠可查看、缓存失效与一次性提示，没有跳过测试。日志和 XML
为本机忽略产物；提交只包含本阶段代码、迁移、测试及文档。

## 范围与剩余限制

Domain 状态机没有新增状态，最小契约变化是服务端保留可复用的本版通过结果及原子保存。
PRODUCT、DOMAIN_MODEL、ARCHITECTURE 和前端使用说明已同步。V13 不迁移历史模型结论：
旧数据库中尚无记录的画像仍需一次真实评估，不能凭浏览器缓存制造通过。

仅保存通过结果，未保存不足评估或问题历史；刷新后的未知不能视为不足。语义判断和
问题质量仍依赖 Provider，本次没有付费验证。SQLite 忙碌可能导致请求失败，但不会绕过
版本检查；用户应查询当前状态，不自动重放。PATCH 仍未新增客户端 expectedRevision 字段，
本次保证加载后到保存期间的原子一致性，不提供完整多客户端编辑冲突解决。

一次性弹窗按本页画像会话抑制，不将敏感业务内容写入浏览器存储。不新增服务端聊天历史、
自动追问、模型重试或后台执行机制；M5 保持暂缓。
