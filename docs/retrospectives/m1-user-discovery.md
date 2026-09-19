# M1 User Discovery 阶段复盘

**Status:** 阶段记录，非 Source of Truth
**Last Updated:** 2026-09-19

> 本文档记录 M1 中 User Discovery 这一半的交付过程与工程教训。
>
> **它不是规范性文档。** 领域规则以 `DOMAIN_MODEL.md` 为准，架构规则以 `ARCHITECTURE.md`
> 与 `AGENTS.md` 为准，里程碑与优先级以 `ROADMAP.md` 为准，长期决策以 `docs/decisions/` 为准。
> 本文档不新增规则，也不替代上述任何文档。若其中内容与它们冲突，以它们为准。

---

## 1. 形成的链路

M1 User Discovery 由 8 个 Task 完成。

| Task | 主题 | 最终产出 |
| --- | --- | --- |
| 1 | User Profile 领域基座 | `UserProfile` Aggregate、`UserProfileId`、`UserProfileStatus`、`Evidence`、revision 语义 |
| 2 | 最小 Application Use Case | Create / Get / Update，以及 `UserProfileRepository` Port |
| 3 | Persistence | `V2__user_profile.sql`、`SqliteUserProfileRepository`、`reconstitute`、revision 内容快照 |
| 4 | REST API | `/api/user-profiles` 的 POST / GET / PATCH |
| 5 | 第一条 AI 链路 | ADR-0003、`DeepSeekAiGatewayAdapter`、`ProfileExtraction`、`/explore` |
| 6 | Sufficiency | `beginReview()`、`ProfileSufficiencyEvaluator`、`/sufficiency-assessment` |
| 7 | Review / Correct / Confirm | 三条状态转换与三个 Review Use Case、三个端点 |
| 8 | Workflow Closure | 子包重构、`RunUserDiscoveryTurnUseCase`、`/discovery-turn`、`EXPLORING` 前置条件 |

最终链路：

```text
创建 User Profile
      ↓
POST /discovery-turn          一轮用户输入
      ↓
ProfileExtraction             当前 Profile + 输入 → AI 建议 → 更新候选 Profile
      ↓
ProfileSufficiencyEvaluator   对「已更新的候选 Profile」判断是否足够
      ↓
insufficient → 返回 missingAreas + nextQuestion，保持 EXPLORING
sufficient   → Domain 执行 EXPLORING → REVIEWING
      ↓
POST /discovery-turn（用户回答上一轮的 nextQuestion）→ 循环跨 HTTP 请求推进
      ↓
GET  /api/user-profiles/{id}  用户查看
PATCH                         用户纠正
      ↓
POST /confirm（携带用户所看的 revision）→ CONFIRMED @ 该 revision
```

`/explore` 与 `/sufficiency-assessment` 保留为更细的单步入口；`/discovery-turn` 是整轮组合。

---

## 2. 反复出现并最终解决的工程问题

这些是**跨多个 Task 反复出现**的问题，不是单次失误。每条都已在 `AGENTS.md` 中有对应规则
（见每节末尾的指向）。

### 2.1 不要把「推导出的结论」写成「文档已确定的规则」

Task 1 中，我从 `DOMAIN_MODEL.md` 的沉默推导出「Evidence 变化不影响 revision」，
并在注释里写成文档必然要求。实际上文档没有规定这件事，那是我的实现选择。

文档没写某件事，**不等于**文档已经决定了它。推导出结论可以，但必须：

```text
要么显式写入对应文档，成为真正确立的规则；
要么在代码里说明这是当前选择，并把它保留为未决问题。
```

不能在注释、提交信息或新规则里把它当成既定前提。

→ `AGENTS.md` §2

### 2.2 多阶段 mutation 不能在失败时暴露半状态

同一个问题在三个 Task 里各出现了一次：

```text
Task 5  AI 建议中途被 Aggregate 拒绝   → 已加载的内存对象留下半更新
Task 6  Update 中途被拒绝              → 同类问题
Task 8  整轮在评估阶段失败             → 本轮已提取的内容被写库
```

最终收敛为两条固定手法：

```text
改动落在一份隔离的候选副本上（UserProfileCandidates）
整轮成功后只保存一次
```

要求是：**任一步失败时，调用方重新读取看到的必须是失败前的状态**——
不只是「存储没变」，已加载的对象也不能停在改了一半的样子。

→ `AGENTS.md` §8.7

### 2.3 不要用 JDK 通用异常承载业务语义

Task 4 与 Task 6 各出现一次，根因相同：

```text
Task 4  Evidence 数组里的 null → NullPointerException → 500
Task 6  非法状态转换 → IllegalStateException → 兜底 500
```

两者都不是服务端故障，而是可预期的客户端冲突。修法是引入项目自己的
`UserProfileStateException` 并在 Interface 层**只映射它**。

反过来，**没有**把 `IllegalStateException` 整体映射为 409：它可能来自 JDK 或第三方库，
一律当成 409 会把真正的服务端故障伪装成可重试的客户端冲突。这与「不要把所有
NullPointerException 映射为 400」是同一条原则。

→ `AGENTS.md` §8.7

### 2.4 结构化输出要验证完整契约，而不只是「能解析」

两次出现：

```text
Task 5  readTree 只读第一个 json 值 → 模型输出后的解释文字被静默忽略并被当成合法结果保存
Task 7  确认请求只带 profileId → 确认的是「服务端当前版本」，不是「用户看过并同意的那一版」
```

「能解析」不是契约。要验证的是完整契约：内容恰好是一个 json 对象、没有多余内容、
必需字段存在、字段之间不互相矛盾，以及**请求携带了它语义上必须携带的东西**。

→ `AGENTS.md` RULE-DOM-003 已覆盖 AI 输出一侧

### 2.5 文档承诺不得强于真实实现

Task 3 中我写了「每个实际产生过的 revision 都保留完整快照」，但真实链路上
revision 是有间隔的——一轮内连续改三个内容区，只提交一次保存，中间的版本从未存在。
注释与迁移文件都做出了实现无法兑现的保证。

修法是**改承诺而不是改实现**：只保存实际提交给 Repository 的版本。

→ `AGENTS.md` §12.6

### 2.6 修复问题时不要无需求地改变已审查通过的其他路径

Task 8 中，为了让「整轮」不能从非 `EXPLORING` 状态开始，我把守卫放进了两个入口共用的
提取步骤——结果连 `/explore` 的既有行为一起改掉了，而那是本 Task 明确要求保持不变的。

修复一个缺陷时，改动范围应当**恰好覆盖缺陷本身**。把守卫移到只有整轮会走的入口，
缺陷照样修好，已审查通过的路径不受影响。

→ `AGENTS.md` §5.2

---

## 3. 遗留项分类

以下问题**已知且有意保留**，不在本阶段处理。

### 3.1 延后处理

```text
UserProfileController 与 Configuration 的 Bean 数量偏多（9 个依赖 / 12 个 @Bean）
    当前不拆。拆分是结构优化，不是缺陷；等出现真实痛点再按关注点拆分。

application 的 shared 子包中三个协作单元是 public
    Java 没有模块内可见性，包拆分时不得不提升。当前没有实际泄漏，
    不为此增加架构校验规则。

Persistence stale-write conflict 仍可能表现为 500
    需要一个 Port 层的异常类型才能安全映射为 409；当前没有并发客户端，
    经 HTTP 不可达。

PATCH 暂无 expected revision / optimistic locking
    两个并发修正仍是后写覆盖先写；每次修正会推进 revision，
    因此后续 confirm 会因 revision 不匹配而失败，不会静默确认错误版本。
```

### 3.2 进入 M2 前重新评估

```text
Confirmation History / confirmedAt /「哪一版曾被用户确认」的持久化
    当前确认只体现为 status = CONFIRMED 与被保留的 revision。
    §9.1 的 UserProfileConfirmed 语义在「该 revision 的内容快照可恢复」这一层成立，
    但「谁在何时确认了哪一版」查不到。
    M2 的 Product Direction 要引用 Confirmed User Profile @ Revision，
    届时需要确认这个粒度是否够用。
```

### 3.3 当前刻意不做

```text
Sufficiency Assessment 历史持久化
    ROADMAP M1 只要求「判断是否足够」，未要求历史。
    评估结果每次重新计算，不落库。
```

---

## 4. 下一阶段入口

M1 的另一半尚未开始：

```text
M1 Repository Analysis

Software Asset 注册
      ↓
用户指定本地 Git Repository
      ↓
确定 analyzedRevision
      ↓
只读 Workspace Adapter（WorkspaceReadPort 的首个真实实现）
      ↓
Repository Profile 生成
      ↓
Repository Profile 与 Evidence 的持久化
```

`WorkspaceReadPort` 已在 M0 定义接口并锁定「Repository Analysis 拿不到写能力」这一契约
（ADR-0001），但至今没有真实 Adapter。这一阶段是它的第一个调用方。
