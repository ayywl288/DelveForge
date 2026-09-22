# M1 Repository Analysis 真实链路验证记录

**Status:** 验证记录，非 Source of Truth
**Last Updated:** 2026-09-22
**Validated Revision:** M1 Repository Analysis（Task 1–8 + Material Selection Smoke Fix）

> 本文档记录 M1 Repository Analysis 在真实环境下的一次端到端验证，以及它暴露并被修复的
> Material Selection 问题。
>
> **它不是规范性文档。** 领域规则以 `DOMAIN_MODEL.md` 为准，架构规则以 `ARCHITECTURE.md`
> 与 `AGENTS.md` 为准，里程碑与优先级以 `ROADMAP.md` 为准。本文档只记录「实际观察到了什么」，
> 不新增规则、不替代上述任何文档。若其中内容与它们冲突，以它们为准。
>
> 它也不是「系统正确」的证明：它验证的是这一条链路在一个具体仓库上确实可用，
> 并如实记录它当时**覆盖到哪里、没覆盖到哪里**。

---

## 1. Validation Scope

### 验证的真实链路

```text
HTTP API
    ↓
Application（AnalyzeRepositoryUseCase）
    ↓
real GitWorkspaceAdapter（真实 git plumbing）
    ↓
固定 analyzedRevision 的 committed tree
    ↓
Material Selection → AI Gateway → real DeepSeek provider
    ↓
RepositoryProfile（Domain）
    ↓
real SQLite / Flyway / MyBatis-Plus
```

全程使用正常启动的应用（`java -jar delveforge-app.jar`）与正常配置：没有替身 AI、
没有 mock Workspace、没有内存 Repository 替身、没有测试专用绕过路径。

### 测试输入

```text
Repository : 一个真实的本地 Java 项目（黑马点评 hm-dianping，Maven + Spring Boot）
日期        : 2026-09-22
两轮分析都固定在同一 commit revision 上（见 §4 的 analyzedRevision）
```

### 本次验证覆盖什么

- 注册 Software Asset → 触发分析 → 读回 Profile 的完整 HTTP 链路；
- `analyzedRevision` 是否等于分析开始时记录的 committed HEAD；
- 两次响应（Analyze 返回值 vs 按标识回读的持久化快照）是否一致；
- 分析是否修改源 Repository（HEAD / working tree / git 对象 / 索引）；
- 未提交内容是否会被误当作分析材料；
- RepositoryProfile 的内容质量与 Evidence 可追溯性；
- 在**修复前后**各跑一次，用于判断 Material Selection 的修复是否真正解决问题。

### 本次验证不覆盖什么

- 多用户、并发、异常路径（这些由自动化测试覆盖，见 `AGENTS.md` §10）；
- 大型 Repository（本次仓库只有 139 个 tracked blob）；
- 「RepositoryProfile 是否足够支撑 Product Direction」——那是 M2 的问题。

---

## 2. Round 1 — 修复前（暴露问题）

### 观察到的结果

链路本身完全可用：注册、分析、持久化、回读一致、源仓库未被修改、未提交内容未进入分析。
**但分析材料里一个源文件都没有。**

```text
tracked blobs                 : 139
selected materials            : 39 files / 164,415 bytes
Java source files in repository: 96
Java source files selected     : 0
```

材料构成几乎全部来自浅层目录：

```text
docs/*（13）  jmeter/*（16）  pom.xml
rocketmq/*（3）  src/main/resources/*（5）  .gitignore
```

### 直接原因

```text
1. 策略只读前 4 层目录，而 Java 包路径 src/main/java/com/hmdp/** 位于第 7–8 层
   → 整个主源码树系统性不可见
2. 不同用途的文件混在一条队列里按路径排序
   → 字典序靠前的类别（docs/、jmeter/）先占满预算
```

### 对结果的影响

Profile 的事实与 Evidence 都是可信的（每条依据都能在仓库中定位），但它描述的是
**这个仓库的文档与工具链**，而不是这个应用本身：

- `purpose` 偏向「一次高并发重构 + 压测验证工作」；
- `modules` 有一半是目录清单式条目（`jmeter/`、`docs/`）；
- `capabilities` 全部由文档描述支撑，没有一条来自代码；
- Evidence 22 条，**引用 Java source 的为 0**。

---

## 3. Material Selection 修复（Round 1 → Round 2 之间）

修复只改 Application 层的选材策略，不改 Domain、不改预算数量级、不引入相关性判断：

```text
1. 不再按目录层级筛选
     它没有节省任何读取（Workspace 列目录本来就要遍历整棵树），
     却会因为「代码按惯例放在深目录」这一常态把主源码树整体藏起来。
     规模改由 maxFiles / maxFileBytes / maxTotalBytes 三个预算界住。

2. 按用途分类别，类别之间轮转取样
     类别：构建元数据 / 源码 / 配置 / 文档 / 脚本 / 其他。
     每一轮从每个类别各取一个，直到预算用尽——任何一个类别都无法凭数量占满预算。
     类别顺序固定、类别内按路径升序，因此结果仍然确定可复现。
```

不变的边界：三个预算、超限文件在读取前按 blob 大小跳过、所有内容经 `WorkspaceReadPort`
从固定 revision 读取、Evidence 的 `sourceRef` 语义（材料中的相对路径）。

---

## 4. Round 2 — 修复后

### 运行结果

```text
HTTP: 注册 201 → 查询 200 → 分析 201 → 回读 200
分析耗时: 约 38 秒（真实 Provider 调用）

analyzedRevision == 分析开始时的 git rev-parse HEAD  : 成立
Analyze 响应 == GET 回读的持久化快照（整份 JSON 深度相等）: 成立
```

### Repository / Git 状态未被修改（两轮均验证）

```text
HEAD 未变；git status 逐行一致；今日新增 git 对象 0 个
.git/index 未被重写（mtime 保持分析前的日期）
被读取的源文件 mtime 保持旧值
```

### 未提交内容隔离（Round 1 验证）

在 Working Tree 中放入一个未提交的假能力文件后再次分析：

```text
analyzedRevision 仍是 committed HEAD
该文件未出现在材料与 Evidence 中
Profile 的任何字段都没有使用它的内容
```

### 材料选材（Round 2）

```text
tracked blobs                     : 139
eligible candidates               : 139
selected file count               : 40（达到 maxFiles 上限）
selected total bytes              : 151,379（< maxTotalBytes 200,000）

BUILD_METADATA : 1 / 1        SOURCE_CODE  : 11 / 106
CONFIGURATION  : 11 / 11      DOCUMENTATION: 10 / 14
SCRIPT         : 1 / 1        OTHER        : 6 / 6

Java source in repository : 96
Java source selected      : 7
```

选中的 7 个 Java 文件全部来自工程配置层（应用入口类与 `config` 包：缓存、MVC、MyBatis、
Redisson、RocketMQ、全局异常处理）。

### 修复前后对比

| 指标 | 修复前 | 修复后 |
|---|---:|---:|
| selected file count | 39 | 40 |
| total bytes | 164,415 | 151,379 |
| **Java source selected** | **0 / 96** | **7 / 96** |
| docs selected | 13 | 10 |
| configuration selected | 10 | 11 |
| scripts selected | 1 | 1 |
| **Evidence 引用 Java source** | **0 / 22** | **7 / 25** |

### Profile 质量变化（模型真实输出，未做任何人工修正）

```text
purpose
  修复前：偏向「一次高并发重构 + 压测验证工作」
  修复后：明确识别产品本身——单体点评 / 秒杀系统后端，并说明它经历的工程改造

modules
  修复前：6 项中 2 项是目录清单式（jmeter/、docs/），没有任何类
  修复后：10 项中 7 项由真实 Java 类支撑（应用入口、缓存 / MVC / MyBatis /
          Redisson / RocketMQ 配置、全局异常处理、Mapper XML）

capabilities
  修复前：全部来自文档描述
  修复后：出现代码级细节——L1 缓存 8 秒过期与容量上限、缓存重建锁的 key 与双重检查、
          RocketMQ 事务生产者替换与消息转换器补装、压测样本的业务结果分组标签

reusableAssets
  修复前：以脚本、文档、Lua 为主
  修复后：前若干项是代码级资产——可直接参考的缓存 / 消息 / 锁 / MVC / 异常处理配置类

techStack
  修复后补上了修复前遗漏的 Lombok、AspectJ、Maven、Spring MVC

limitations / risks
  两轮都有具体依据，且没有因为读到源码而开始产生无证据推测：
  「未见集群 / 高可用相关代码或配置」这类表述以「未见」陈述可见范围，
  而不是断言「项目没有」
```

### Evidence 验证

```text
Evidence total                  : 25（唯一 sourceRef 25 个，无重复）
  引用 Java source              : 7
  引用 docs                     : 5
  引用 config                   : 3
  引用 build metadata           : 1
  引用 scripts / other          : 9

sourceRef 在 analyzedRevision 上不存在 : 0
非 blob（目录等）                       : 0
```

**所有引用 Java source 的 Evidence 都做了逐条人工核对，全部是内容级证据，而不是「文件存在」**：

- 「L1 缓存 8 秒过期、容量上限 10000/5000」——与配置类中的常量逐一对应；
- 「注册了登录 / Token 续期拦截器」——与 MVC 配置中注册的类名逐一对应；
- 「全局 RuntimeException 返回固定文案」——与异常处理类中的处理分支和返回文案对应；
- 「应用入口启用 AspectJ 自动代理、定时任务与 Mapper 扫描」——与入口类注解逐一对应；
- 其余（配置、Mapper XML、JMX、压测脚本、对账脚本）同样逐条与文件内容核对，未发现编造。

结论：**修复后 Profile 中的技术事实全部可回溯到 committed source，没有发现无依据的技术性 claim。**

---

## 5. Known Limitation

修复解决的问题是**系统性**的（整个主源码树不可见），但**没有**解决"哪些实现最能代表这个仓库"：

```text
1. SOURCE_CODE 类别内仍然采用 deterministic path order（按相对路径升序）
     因此排到名额的是路径序靠前的包，而不是更能代表业务实现的文件。

2. 当前选中的 Java 主要是工程配置类（应用入口与 config 包）
     controller / service / entity / mapper 接口等业务实现没有进入材料。

3. 上一轮曾入选的秒杀业务脚本（Lua）本轮落选
     类别轮转让预算重新分配，这是此消彼长，不是纯粹改善。

4. 部分重要文件因 maxFileBytes（20KB）被跳过
     包括多级缓存的实现类本身、数据库初始化脚本；
     这是"不截断、读不到就不送"的明确取舍，但也意味着大文件内容完全不可见。
```

当前策略保证的是：

```text
bounded representative sampling
```

而不是：

```text
完整或深度的 Repository understanding
```

**需要明确的是：在有限 context budget 下选择「最具代表性的业务实现代码」，是一个独立的
Repository Understanding 问题**，而不是当前选材逻辑的一个小修补。它需要回答"什么算代表性"
（业务入口？变更热点？被引用最多的模块？），并对其判断质量负责。把这个判断塞进现有的
路径排序或再加一个权重常量，只会把问题往后推。

---

## 6. Stop Decision

M1 阶段到此停止继续优化 Material Selection，理由是当前状态已经满足 M1 的目标：

```text
RepositoryProfile @ analyzedRevision
```

并且该结果满足：

```text
structured profile      结构化的 purpose / techStack / modules / capabilities /
                        reusableAssets / limitations / risks
traceable Evidence      每条依据都能在记录的 revision 上定位到真实文件
fixed revision          整个分析固定在一个已解析的 commit 上
persistence             快照按标识持久化，回读与返回值一致
source repository safety 分析全程只读，未提交内容不进入分析
real end-to-end         real Provider / Git / HTTP / SQLite 全链路真实执行
```

**材料代表性的局限不阻塞「可信输入」这一目标**：M1 需要的是"能够追溯到确定 revision、
依据可核对的分析结果"，这一点已经成立——上述 Known Limitation 限制的是**广度**，
不是**可信度**。

更深入的 material relevance / repository understanding，只有在后续真实需求暴露时才重新评估
（见 §7），不因为它"看起来可以更好"就继续加机制。

---

## 7. Revisit Conditions

满足以下任一条时，重新评估 Material Selection：

```text
1. M2 Product Direction 因 RepositoryProfile 缺少关键实现事实而明显失真
     （方向建议停留在文档描述层面，无法指出可复用的具体实现）

2. 在真实 Repository 上经常采样不到核心业务实现
     （当前的问题在普通 Java 工程上是可复现的，不是个例）

3. 大型 Repository 暴露当前 deterministic sampling 的明显局限
     （例如候选数量使现有预算下的采样明显不代表整体）

4. 后续产品能力明确需要 implementation-level 的 Repository understanding
     （例如 Evolution Planning 需要知道某能力的实现位置与依赖）
```

届时的重新评估应当以真实需求与证据为起点，而不是以"当前排序不够聪明"为起点。

---

## 8. 如何复现

```text
1. 以正常方式启动 Backend（默认读取环境变量中的 Provider 凭据，本记录不含任何凭据）
2. POST /api/software-assets            注册该本地 Repository 的 Software Asset
3. POST /api/software-assets/{id}/analysis   触发分析（真实 Provider 调用）
4. GET  /api/repository-profiles/{id}   回读持久化快照
5. 用 git rev-parse HEAD 与 Profile 的 analyzedRevision 比对
```

材料选材是确定的：只要 revision 与策略不变，选中的文件集合与顺序完全可复现，
因此可以在不调用 Provider 的情况下用 `git ls-tree` 独立复算并与 Evidence 交叉核对。
