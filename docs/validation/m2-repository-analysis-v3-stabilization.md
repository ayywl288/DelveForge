# M2 — Repository Analysis V3 稳定化与标定

**Status:** Validation Record / **Not Source of Truth**
**Last Updated:** 2026-10-03
**标定实验基线:** `main` @ `7209cd2`（Go 分类修正 + Scout 有界重试；A/B 实验就是在它之上跑的）
**最终验证版本:** `3f55678`（定下 `24 / 229376` 并补齐文档的那个提交）
**性质:** 稳定化与标定，**不是重新设计**。核心链路（Map → 两条通道 → flat/分层 Scout →
定向候选 → ReadPlanner → 凭据边界 → 真实读取 → Final Analyzer → Evidence 校验 → Profile）保持不变。
**依据:** ADR-0004 / ADR-0005 / ADR-0006、
`docs/validation/m2-repository-analysis-v3-code-walkthrough-probe.md`、
`docs/validation/m2-repository-analysis-v3-multi-repository-smoke.md`

> 本文档记录本轮**实际做了什么、据此定了什么**。历史验证记录未被改写；
> 上一轮 Smoke 的观察仍然有效，只是在本轮被处理或被明确接受。

---

## 1. 本轮处理了四件事

```text
① 定向源码材料预算标定        已定：24 / 229376（原 18 / 163840）
② 分层 Scout 守卫标定         已定：**维持不变**（12 / 18 / 8 / 6）
③ Scout 契约违反的有界重试    已实现（一次，且占用真实调用额度）
④ Go *_test.go 分类           已修正（TEST_CODE → NONE）
```

### 1.1 明确**没有**做的事

```text
没有测试配置 C（targeted max-files = 30）
没有为 langflow 调高任何守卫
没有重跑五仓库 Provider Smoke
没有放松任何解析 / 引用校验
没有改凭据边界语义
没有动 Foundation 通道的默认值
```

`targeted max-files = 30` 与进一步的 langflow 守卫标定**被有意停止**：
A/B 已经足以判断（见 §2），而 C 与更宽的守卫只会带来更高的单次成本与更多的模型调用，
边际收益递减。这写在 §5 的"有意停止"一节。

---

## 2. 定向源码材料预算：A/B 标定

### 2.1 方法

配置（**只改 `application.yml` 覆盖值，不改代码**），同一批固定 revision，同一批真实仓库：

```text
A   targeted-source.max-files = 18   max-total-bytes = 163840   ← 原默认
B   targeted-source.max-files = 24   max-total-bytes = 229376
```

`max-total-bytes` 必须跟着改，否则文件数只是理论值——这一点在本轮被真实数据证实（见 §2.3）。

### 2.2 实测（各仓库各跑一次，全部 201）

| 仓库 | 配置 | 耗时 | Region / File / Final 调用 | 重试 | 模型可见文件 | Foundation / targeted | 内容字节 | Final Analyzer 请求体 | Evidence |
|---|---|---|---|---|---|---|---|---|---|
| hm-dianping | A | 56.2 s | 0 / 1 / 1 | 0 | 30 | 12 / **18** | 107,100 B | 112,899 B | 31 |
| hm-dianping | B | 69.8 s | 0 / 2 / 1 | **1** | 36 | 12 / **24** | 145,822 B | 153,274 B | 34 |
| mall | A | 138.8 s | 1 / 6 / 1 | 0 | 29 | 11 / **18** | 137,085 B | 143,774 B | 25 |
| mall | B | 115.2 s | 1 / 6 / 1 | 0 | 35 | 11 / **24** | 154,276 B | 162,271 B | 16 |
| memos | A | 213.4 s | 3 / 13 / 1 | **1** | 28 | 12 / **16** | 203,473 B | 214,016 B | 25 |
| memos | B | 207.6 s | 3 / 12 / 1 | 0 | 36 | 12 / **24** | 244,620 B | 257,404 B | 32 |
| awesome-llm-apps | A | 204.4 s | 1 / 6 / 1 | 0 | 29 | 11 / **18** | 142,351 B | 151,560 B | 29 |
| awesome-llm-apps | B | 186.5 s | 1 / 6 / 1 | 0 | 35 | 11 / **24** | 198,164 B | 210,750 B | 28 |

`Region / File` 列是**实际 Provider 调用次数**（含重试），取自聚合日志的
`regionScoutAttempts` / `fileScoutAttempts`；与调用切片逐条核对一致。

### 2.3 关键证据：18 那一档的文件数"有一部分只是理论值"

```text
A 的 targetedSourceSelectedCount   18 / 18 / **16** / 18
B 的 targetedSourceSelectedCount   24 / 24 /   24 /  24
```

**memos 在 A 下只取到 16 个**：不是 Scout 没指出足够的候选（合并后有 300+），
而是第 17、18 个候选放不进 `max-total-bytes = 163840` 的剩余额度，
被记为 `EXCEEDS_REMAINING_TOTAL_BYTES`。

这正是任务书担心的那种情形：**只调 `maxFiles` 而不调总量，文件数会成为空头承诺**。
因此 B 的两项是配套定的。

### 2.4 覆盖面的实质变化（离线计算，无额外 Provider 调用）

按模型可见材料的路径集合比较。**注意：A 与 B 是两次独立的模型调用**，
Scout 的分支取舍不完全相同，因此差异里含有模型噪声；下面只列出**结构上成规律**的部分。

```text
mall              六个模块**每一个都恰好 +1**
                  mall-admin 6→7 · mall-portal 3→4 · mall-mbg 3→4
                  mall-search 3→4 · mall-common 4→5 · mall-security 3→4
                  新增的是 UmsAdminController / UmsMenuController /
                  UmsResourceController / UmsRoleController —— 控制器层

hm-dianping       src 20→26；新增的是 entity/ 领域模型
                  Shop / ShopType / Voucher / VoucherOrder

memos             首次出现 internal/（0→2，internal/email/…）与 proto/（0→1）；
                  web 11→15

awesome-llm-apps  每个顶层集合各 +1（advanced_ai_agents 10→11、
                  rag_tutorials 3→4、voice_ai_agents 3→4 …）
```

`mall` 的"+1 × 全部六个模块"是**轮转结构本身**的体现：合并顺序是保序轮转，
A 的 18 个正好在第三轮中途停下，B 的 24 个正好跑满四轮 × 六分支。
这不是"多了 6 个文件"，而是"每条分支多拿到一轮"。

### 2.5 代价

| 维度 | A → B |
|---|---|
| Final Analyzer 请求体 | 113→153 KB · 144→162 KB · 214→257 KB · 152→211 KB（**+13% ~ +39%**） |
| 内容字节 | +36% / +13% / +20% / +39% |
| 单次耗时 | **无回归**（4 个仓库里 3 个 B 更快；差异由模型生成速度主导，不是预算造成） |
| 模型调用次数 | 不变（材料预算只影响读多少，不影响问几次） |

最大观测请求体 257 KB，仍在常规模型上下文之内。

### 2.6 决定

```text
adopt   targeted-source.max-files      = 24
        targeted-source.max-total-bytes = 229376     （≈ 9.5 KB/文件 的余量）
keep    targeted-source.max-file-bytes  = 65536      未改动（无证据要求提高）
keep    foundation 通道                 完全未改动
```

**理由（按权重）**

1. **18 在 memos 上不成立**：文件数取不满，且原因是总量上限先到。一条"文件数有一部分是
   理论值"的默认值不适合作为默认值。
2. **24 是让文件数真正成立的最小档**：四个仓库全部取满 24，执行期没有因总量再丢文件
   （`totalBudgetExceededCount = 0`）。
3. **覆盖面的提升是结构性的**，不是数量堆砌：多出的正好是一轮轮转，落到控制器层、领域模型层
   与之前完全缺席的顶层目录上。
4. **代价可接受且没有回归**：请求体 +13%~+39%，延迟没有变慢。
5. **没有继续往上试**：30 那一档没有证据表明还有结构性收益（轮转已跑满四轮），
   而每次分析的成本是线性增长的。选取最小值、不为求"数学最优"继续试探。

**已知的紧凑性**：memos 在 B 下 targeted 内容约 205 KB，占 `max-total-bytes` 的约 89%。
也就是说这个上限在观测到的最坏仓库上已经接近吃满。这与"文件数不是空头承诺"是一致的
（取满了，且没用执行期剔除兜底），但**如果将来有更大的仓库再次先撞总量上限，
应按新的观测再调，而不是现在预先放大**。

---

## 3. 分层 Scout 守卫：维持不变

### 3.1 决定

```text
region.max-scout-calls        = 12   不变
scout-calls.max-total         = 18   不变
region.max-rounds-per-branch  =  8   不变
region.max-selected-regions   =  6   不变
region.max-catalog-bytes      = 65536 不变
scout.max-catalog-bytes       = 65536 不变（同时是 flat 判据与分支判据）
```

没有做进一步的 langflow 标定。既有的 langflow 观测**没有证明这些守卫彼此不自洽**：
当时绑定的是 `max-scout-calls = 12`，而深度（5/8）与载荷（最大 6.6 KB / 64 KB）都远未触及，
`max-total = 18` 与 `region = 12` 的包络关系（12 + 6 = 18）也成立。

### 3.2 明确记录的产品边界

```text
当前 MVP 支持对普通小/中型仓库、以及相当一部分大型仓库的有界分析。

当分层导航在配置的 Scout 预算内无法把候选压进预算时，
极端庞大或极宽的仓库会**失败关闭**并以 409 RepositoryNotAnalyzable 结束，
不会截断分支、不会采样、不会返回部分 Profile。
```

**langflow 是这条边界当前的实例**：10,375 个 Map 文件 / 4,572 个源码候选 /
889 KB flat catalog（13.6×），分层导航在 12 次 Region 调用内没有走完，返回 409。

**这不是实现缺陷**，而是当前成本包络下的产品边界：

```text
无限放宽守卫确实可能让它成功，但那意味着一次分析要反复付出十几次、几十次
模型调用去换一个仓库，而 MVP 没有证据表明这种仓库是主要用例。
把它记为边界、而不是把整个设计为它优化，是本轮的选择。
```

### 3.3 ADR-0005 的关系

ADR-0005 把守卫定义为"暂定值，必须在实现时用真实数据校准"，其 Revisit Condition 1
（"守卫在真实仓库上被证明过紧或过松"）因此**已被触发并有结论**：
本轮用真实数据确认了 **`max-scout-calls` 是超大仓库上的绑定约束**，
并决定**保持 12**（理由是产品边界，不是数据不足）。该结论已补记在 ADR-0005。

---

## 4. 已实现的两处修正

### 4.1 Scout 契约违反的有界重试

**背景**：上一轮 Smoke 里 memos 的第一次分析因为 File Scout 返回了 **7 个 `focusAreas`**
（契约上限 6）而整次 502；同一资产、同一 revision 再跑一次即成功。
模型偶发不守约定，而**一次不合规就作废整次分析**。

**实现**

```text
ScoutProtocolViolationException   模型这次没按 Scout 输出契约作答（继承 AiGatewayException）
ScoutAttemptPermit                每次 Provider 调用之前的许可；由持有预算计数的一方提供
```

```text
第 1 次尝试   acquire → 调用 → 解析 → 引用校验
   ├─ 成功         → 返回
   ├─ 调用失败     → 原样抛出（**不重试**）
   └─ 契约违反     → acquire → 第 2 次尝试
        ├─ 成功     → 返回
        └─ 仍不合法 → 原样抛出（同样的失败语义）
```

**没有放松任何校验**：7 个区域不会被截成 6 个，不存在的引用不会被丢弃，
解析器与解析器（Resolver）的规则一字未改。重试只是"拒绝并再问一次"。

**不重试的失败**：Provider/传输失败（无 key、超时、非 2xx、空响应体）、
Workspace 失败、材料/凭据边界失败、最终分析器的一切失败——这些再问一次都不会更好。

**Region Scout 与 File Scout 同一条原则**：两者都可能因为模型不守约定而失败，因此共用同一个实现。

**上限为 1**：本轮没有证据表明需要更多次。

### 4.2 重试计入真实调用额度

**重试是一次真实的模型调用**，因此它不能藏在"逻辑 Scout 阶段数"里。

```text
RepositoryRegionNavigator  → 许可 = claimRegionScoutAttempt(prefix)   记一次 Region 尝试
RepositoryBranchScoutRunner → 许可 = claimFileScoutAttempt(group)     记一次 File 尝试
RepositoryUnderstanding     → flat 路径的许可                         记一次 File 尝试
```

- 许可在**每一次尝试之前**申请；预算不允许时**抛出，那一次调用不会发出**。
- 守卫语义不变：`region.max-scout-calls` 与 `scout-calls.max-total` 现在约束的是**尝试次数**。
- `max-rounds-per-branch` **仍约束逻辑深度**：同一个节点重试一次不构成"更深一层"。
- 聚合日志新增 `regionScoutAttempts` / `fileScoutAttempts` / `scoutAttempts`，
  因此"看起来问了 7 次、实际问了 9 次"这件事是可读的。

**真实运行里已经发生**（见 §2.2 的"重试"列）：

```text
A-memos   fileScoutAttempts = 13  → 12 次逻辑分支调用 + 1 次重试
B-hm      fileScoutAttempts =  2  → flat 路径的 1 次调用 + 1 次重试
```

8 次标定运行里有 2 次触发重试——上一轮需要人工重跑的那种失败，现在自愈了。

### 4.3 Go `*_test.go` 分类修正

`RepositoryPathClassifier` 已有"按各语言自身惯例识别测试"的规则
（`*Test.java` / `*.test.ts` / `test_*.py` / `*.jmx`），缺的是 Go 的 `*_test.go`。
补上这一条后缀即可，没有新增任何抽象：

```text
真实效果（memos，同一 revision）
  SCOUT_SOURCE      880 → 777     （103 个 Go 测试文件移出）
  NONE              284 → 387
  flat File Catalog 141,079 B → 125,413 B   （仍超限，因此仍走分层）
```

回归覆盖：`*Test.java` / `*.test.ts` / `test_*.py` / `*.jmx` 的行为不变；
且**不**把 `contest.go` / `TestUtils.go` / `latest.go` 判成测试（Go 没有 Java 那种类名惯例）。

---

## 5. 有意停止的工作

```text
配置 C（targeted max-files = 30）
    A 已经暴露了 18 的实质问题，B 已经在结构上取满一轮轮转。
    再往上没有证据表明还有结构性收益，而成本线性增长。

langflow 的守卫标定
    绑定约束（max-scout-calls）已定位，深度与载荷都远未触及。
    放宽它可能让单个仓库成功，但也要为它单独调整个设计的成本包络。
    本轮的结论是把它接受为 MVP 边界，而不是继续为它优化。

第五个仓库的额外 Smoke
    上一轮五仓库 Smoke 的观测（langflow 409 之外全部成功）在本轮仍然有效，
    且本轮已有 8 次真实运行作为稳定化证据。
```

---

## 6. 验收对照

```text
1. 材料预算已经用真实数据标定                      ✅ §2（A/B，8 次真实运行）
2. 分层守卫有明确的真实数据理由                    ✅ §3（保留 12，理由与边界都写明）
3. 一次有界 Scout 重试，且未削弱校验               ✅ §4.1（4 + 2 + 1 + 1 个新用例）
4. 实际 Provider 尝试次数对预算可见                ✅ §4.2（许可 + 聚合日志三个新字段）
5. *_test.go 是 TEST_CODE                          ✅ §4.3（真实数据 880 → 777）
6. 小/中型仓库仍然健康                             ✅ §2.2（hm/mall/awesome 全部 201，flat 路径未回归）
7. langflow 有明确的"支持或边界"结论               ✅ §3.2（接受为边界，不是缺陷）
8. 凭据边界语义未变                                ✅ 未触碰；secretExcludedCount 仍如实记录
9. 任何失败路径都不落部分 Profile                  ✅ 未触碰保存语义；重试失败仍失败关闭
10. ./mvnw clean verify 通过                       ✅ 见 §7
```

---

## 7. 测试与验证

```text
./mvnw clean verify → BUILD SUCCESS
domain 178 / application 653 / infrastructure 108 / app 130 = 1069 tests，0 failures
```

本轮新增 9 个用例，全部落在行为上：

```text
application  +8   Go 测试分类 2（识别 *_test.go、不误伤 contest.go 一类）
                  Scout 有界重试 4（不合法→重试成功 / 两次都不合法→失败 /
                                   调用失败不重试 / 许可拒绝时重试不发出）
                  Understanding 重试与记账 2（重试成功、额度用尽时重试不发出）
app          +1   装配约束：定向源码的 24 / 229376 已生效且总量容得下文件数
```

标定用的脚本与原始运行记录留在 `target/`（未提交），
它们只是观测工具，不构成生产代码的一部分；本轮结束后已从 `target/` 清掉
（摘要与结论保存在本文档 §2）。

---

## 8. 剩余风险

```text
1. targeted-source.max-total-bytes 在观测到的最坏仓库（memos）上已用到约 89%。
   更大的仓库可能再次先撞总量上限——那时应按新观测调整，而不是预先放大。

2. 重试上限为 1。模型连续两次不守约定仍会让整次分析失败。
   本轮把"一次自愈"作为取舍，没有证据支持更多次。

3. 材料预算的 A/B 比较是**两次独立模型运行**之间的比较，不是受控实验：
   Scout 的分支取舍不完全相同，差异里含模型噪声。
   结构性证据（mall 六个模块各 +1、轮转恰好跑满一轮）是主要依据，不是计数差异。

4. langflow 这类仓库仍在 MVP 包络之外（409，失败关闭）。
   这是产品边界而非缺陷，但没有数据说明它在真实用户里占多大比例。

5. 凭据边界的已知残留（未知格式 / 编码 / 分片构造）与 `.env.example` 被整份排除
   都**不在本轮范围**，维持原状（ADR-0006 的"不保证"一节仍然有效）。
```

---

## 9. 复现

```text
1. 构建
   ./mvnw -q -DskipTests package

2. 记录代理（只读观测，不记录请求头）
   python recording_proxy.py 8799 calls.jsonl

3. 应用（材料预算用命令行覆盖，不改代码）
   java -jar delveforge-app.jar \
        --delveforge.persistence.database-file=<tmp>/smoke.db \
        --delveforge.ai.deepseek.base-url=http://127.0.0.1:8799 \
        --delveforge.repository-analysis.targeted-source.max-files=24 \
        --delveforge.repository-analysis.targeted-source.max-total-bytes=229376

4. 每个仓库
   POST /api/software-assets  →  POST /api/software-assets/{id}/analysis
   聚合日志给出 regionScoutAttempts / fileScoutAttempts / scoutAttempts

5. 离线度量
   python analyze_runs.py <indexDir> <runJson...>
```

---

## 10. 未改动确认

```text
生产链路结构 / Prompt / 解析与引用校验规则 / 凭据边界语义   未改动
Foundation 通道默认值                                     未改动
分层守卫默认值                                            未改动（§3）
历史验证记录                                              未改写
测试仓库 HEAD 与工作树                                     未变
```
