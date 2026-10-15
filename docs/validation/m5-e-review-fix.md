# M5-E 独立复审修复 — M5-010

- 日期：2026-10-16，Asia/Shanghai。
- Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。
- 分支：`feature/m5-lab`。
- 基线：`7122b5bb3fe12b583ad0d238926beb7196025c0a`。
- 结论：本地修复及复现回归通过，等待独立复审；不代替独立 Gate。

## 复现与根因

在生产源码修改前，新增两个独立 `createSession()`，共享隔离的 jsdom localStorage 和按锁名
排队的 Web Lock 模型。GET / API POST 由可控 Promise 挂起，storage 事件手动延迟或送达：

1. A 保留旧 Retry receipt，挂起 GET。B GET 证实 Retry 已授权并正常清除旧标记，再明确执行
   Execute，写入新 Execute receipt，POST 保持未决。A 的旧 READY GET 最后返回；旧逻辑根据
   内存 Retry 证明无条件 removeItem，B 的持久化标记变成 null。
2. 相同交错中，在 A 的 GET 返回前送达新 receipt 的 storage 事件。旧 GET 返回同父 Retry 的
   terminal 快照；旧逻辑在响应完成时才取 receipt，借用了新 Execute 的内存标记和父 ID，
   再次无条件删除 B 的标记。没有证明此 GET 对应新请求。

两条断言都要求保留 B 发送时的原始存储值；基线运行 **2/2 失败**，实际均为 null，日志
`m5e-010-red.log`。不是只检查最终状态或模拟删除函数。

根因有三层：清理依据在 GET 完成时才读取；Execute receipt 没有独立请求身份；读写路径虽有
长时操作锁，后台 GET 的 clearReceipt 却不加锁且不重查当前持久化值。仅比较父 Retry ID
不能区分同一授权下的先后请求，锁外“先检查、后删除”也不能解决替换竞态。

## 最小修复

只修改生产前端 `frontend/src/state/session.ts`：

- 每次 Execute / Repair / Rollback / Retry 新建随机 requestId，独立于业务 authorizationId
  和 retryAuthorizationId。字段只在浏览器存储使用，不发送为后端授权，也不改变 Retry 语义。
- refreshPlan 在请求开始时复制预期 receipt，不借用后到 storage 事件中的新标记。原有完成
  状态、授权事实、Candidate / baseline 与已采纳 GET 规则保留。
- 所有 receipt 写入和清理统一经过 `delveforge.execution-receipt` 独占 Web Lock。
  reconcileReceipt 在锁内重读存储，核对 requestId 及完整内容，再重查查询版本，才允许删除。
  实际标记已替换时只同步新标记；没有匹配证明时不清理。
- 短时锁内没有 GET / POST 等待；既有 `delveforge.execute` 长时操作锁保留，不重入它。
  从锁排队返回后再次检查快照是否仍被采纳；失效 refreshPlan 返回 null。
- writeReceipt 在同一短时锁内确认存储仍为空和查询仍有效，才写入。错过 storage 事件、
  损坏 / 不可读存储、其他请求已写入时都不能覆盖并发送。没有自动重试。
- 业务拒绝的清理路径也传入本次 marker 和当前查询守卫，覆盖 Execute、Repair、Rollback、
  Retry，而非只改正常 GET。
- 兼容旧版没有 requestId 的现存标记：比较其完整内容，保持原 GET / 终态规则；新写入一律
  带新身份。不在 GET 中制造业务授权、不重放命令。存储暂时缺失也不能抹去仅在内存中的
  UNKNOWN，除非该内存请求本身已得到匹配的完成证明。

同类入口检查：生产源码所有 executionKey 的 setItem / removeItem 已收敛到上述两个 helper；
storage 事件读取当前存储，不信任事件携带的迟到 newValue。导航清理仅处理导航 key，不删 receipt。

测试支持变更：`frontend/tests/webLocks.ts` 提供同名排队、不同名独立和 ifAvailable 竞争返回 null
的确定性锁模型，由共享 setup 与新回归共用。旧 mock 曾把所有锁名错误地视为同一把锁，且无
ifAvailable 时也返回 null；改为符合本次使用方式的模型，没有放宽任何旧测试断言。
Architecture 补充客户端锁协议，原 M5-E 报告只追加本修复索引，保留旧证据。

## 26 项新增回归

| 覆盖 | 数量 | 检查事实 |
| --- | ---: | --- |
| 两条原始双 Session 复现 | 2 | storage 延迟 / 已送达两种情况下，旧 GET 都不能删除新 Execute |
| Execute / Repair / Rollback / Retry 交替 | 4 | A 的旧 Plan/Copy GET 不能删除 B 后续 UNKNOWN；迟到删除事件、重载仍保留新标记，只有显式一次 POST |
| 清理排队期间替换 nonce | 1 | 其余所有字段相同、仅 requestId 不同也不得删除；后续匹配 GET 能正常清理 |
| 清理等锁期间查询被更新 | 1 | 锁内重查快照，旧 GET 返回 null，未决授权仍阻塞 |
| GET 两种返回顺序加 storage 替换 | 2 | 被淘汰 GET 不清理新请求 |
| 四种操作正常写入 / 完成 | 4 | 每次 set / remove 均持有同名短时锁；新 requestId 独立；正常结果清理，不死锁 |
| 四种旧格式标记正常完成 | 4 | 原有等待标记没有永久卡住，不发 POST |
| 同父 Retry、同一时间的显式执行 | 1 | 两次请求仍有不同 requestId，无自动重送 |
| EXECUTING / VERIFYING / ROLLING_BACK 重载 | 3 | 仅 GET，已有标记保持，不能重发 |
| GET 起点没有 receipt | 1 | 后到事件不能把新请求交给旧 GET 清理 |
| 发送前的持久化替换 | 1 | 错过事件也不覆盖另一个标记，不 POST |
| 写锁排队后资格过期 | 1 | 重新核对查询版本，拒绝写入和 POST |
| 不支持锁 / 存储异常 | 1 | 不清理，不覆盖损坏标记，不 POST |
| 合计 | 26 | 与既有 174 项合并为 200 项 |

## 实际命令与结果

均在上述 Worktree 根执行，API 全部为测试 spy / 可控 Promise，存储仅为 jsdom 测试环境。
未启动真实后端、浏览器用户配置、Git Fixture 或数据库，不调用 Provider。

```powershell
pnpm --dir frontend test -- receipt-race.test.ts
pnpm --dir frontend test -- final-ux.test.ts receipt-race.test.ts
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend build
git diff --name-only -- backend
git diff --check
```

| 检查 | 实际结果 | 日志 |
| --- | --- | --- |
| 修复前双 Session 复现 | 2 failed / exit 1；均实测新持久化 receipt 被删 | `m5e-010-red.log` |
| 首次修复后同两条 | 2 passed / exit 0 | `m5e-010-green-initial.log` |
| 最终竞态聚焦 | 26 passed / exit 0 | `m5e-010-race-final.log` |
| 前端全量 | 200 passed，12 文件全通过 / exit 0；00:31:26 开始，11.85 s | `m5e-010-front-final.log` |
| typecheck | vue-tsc -b / exit 0 | `m5e-010-type-final.log` |
| build | vue-tsc -b 与 Vite 成功 / exit 0；56 modules | `m5e-010-build-final.log` |
| 后端 Diff | 空；backend / REST / schema / API client 未修改 | Git Diff 检查 |
| Diff 格式 | git diff --check 通过 | 提交前检查 |

开发中的首轮全量为 174 passed / 2 failed（共 176），失败在既有 UI 内存 UNKNOWN 夹具，
因为修复初版把“存储为空”误当作内存等待可取消。生产逻辑已改为仍需匹配请求和完成证明；
旧断言未变。直接兼容回归 14 项通过，之后最终 200 项全量通过；未用改断言掩盖此回归。

后端本轮没有变更，也未运行 Maven 或后端测试；本次新 requestId 不改变 API 请求字段。
基线 M5-E 的 1,814 项后端 verify 为 2026-10-15 的历史验证，未冒充本轮重跑。当前改动的
适用验证为前端 M0–M4 / M5-A/B/C/D/E 已有回归及新增 receipt 交错覆盖。
日志和前端 dist / 构建缓存均不纳入提交。

## 剩余风险与 Decision Required

- 交错证据来自真实 Session 逻辑、共享 jsdom 存储和确定性 Web Lock 队列模型；未做真实
  浏览器多窗口 E2E、OS / 浏览器进程强制崩溃或断电测试。
- Web Lock 只协调同源且遵守协议的客户端；手工修改存储、扩展或仍运行未修复旧代码的页面
  不受新协议约束。旧格式数据兼容不代表旧代码也获得修复。存储和锁不可用时仍 fail closed。
- 不改变后端 UNKNOWN / RECOVERY_REQUIRED 与授权事实；重启未决调用的所有权仍留待后续
  Recovery。未实现新 Recovery、Verification 改造或 Git/API 性能优化。
- 无本 Slice 新增的阻塞业务决策。提交只在 feature/m5-lab 本地完成；最终 SHA 由交付回复
  与 git show HEAD 提供。提交后停止等待独立复审，不 Push / 合并 / 移动 Tag。
