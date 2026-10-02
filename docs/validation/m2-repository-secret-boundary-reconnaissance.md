# Repository Source Secret Boundary — 代码路径侦察（M2 Task 10B-1）

**性质：** 实现前侦察。只读当前代码与配置，**未修改任何生产代码**。
**目的：** 为 ADR-0006 提供可核对的代码事实，而不是从 ADR 推断结论。
**日期：** 2026-10-02

> 本文只记录**读代码能确认的事实**。凡属推断或未验证，都在 §6 显式标出。

---

## 1. 仓库文件内容的真实流向

一次成功的 Repository Analysis（当前生产链路）中，仓库文件内容经过的每一处：

```text
GitWorkspaceAdapter.readFile                               infrastructure/workspace/GitWorkspaceAdapter.java:121
    git cat-file blob <commit>:<path>  →  new String(stdout, UTF_8)      :128-134
        ↓  内容进入 Application 的唯一入口
RepositoryReadExecutor.readLane                            readplan/RepositoryReadExecutor.java:124
    workspace.readFile(ref, analyzedRevision, path)                          :134
    执行期尺寸复核（maxFileBytes / maxTotalBytes，作用于**真实内容**）
        ↓
new RepositorySourceFile(relativePath, content)                            :151
        ↓
RepositoryReadResult.material
        ↓
RepositoryUnderstanding.understand                          workflow/RepositoryUnderstanding.java:216
    readExecutor.execute(readPlan, workspaceRef)                             :216
    return read.material()                                                   :224
        ↓
AnalyzeRepositoryUseCase.analyze                            workflow/AnalyzeRepositoryUseCase.java:156
    material = repositoryUnderstanding.understand(ref, revision)             :157
        ↓
RepositoryAnalysisExtraction.extract                        extraction/RepositoryAnalysisExtraction.java:110
    aiGateway.generate(buildRequest(files))                                  :114
        ↓
    describeFiles(files)                                                     :187
        entry.put("path", file.relativePath())                               :191
        entry.put("content", file.content())                                 :192   ← 原样
        payload.put("repositoryFiles", material)                             :197
        objectMapper.writeValueAsString(payload)                             :200
        ↓
白名单：AiRequest(SYSTEM 指令 + USER=上述 json, JSON 模式)                     :174-178
        ↓
DeepSeekAiGatewayAdapter.generate                           infrastructure/ai/DeepSeekAiGatewayAdapter.java:56
    POST {base-url}/chat/completions                                           :58-61
        ↓
        **离开本机**
```

**结论（代码事实）：**

```text
1. 仓库文件内容进入 Application 的唯一入口是 RepositoryReadExecutor.readLane 里的
   WorkspaceReadPort.readFile（生产代码中只有这一处调用；见 §3）。
2. 内容离开本机的唯一出口是 RepositoryAnalysisExtraction.describeFiles
   —— 它是最后一个由 Application 控制的、内容仍是原文的位置。
3. 两者之间没有任何转换、脱敏、过滤或长度裁剪：RepositorySourceFile 的 content
   从读到写一路原样传递。
```

## 2. 哪些材料类型可能携带原始仓库内容

一次分析的材料由两条通道合成（`RepositoryReadPlanner`），**两条都可能携带原始内容**：

| 通道 | 候选来源 | 是否含原始仓库内容 |
|---|---|---|
| Foundation | `RepositoryCandidateLane.FOUNDATION`：构建元数据、配置、部署、数据模型、文档、脚本 | **是** |
| 定向源码 | `RepositoryCandidateLane.SCOUT_SOURCE`（经 File Scout 或分层 Scout 指出） | **是** |

两条通道在 `RepositoryReadResult.material` 首次合流，此后不再区分来源地送进同一个请求。

### 2.1 高危文件当前**全部**落在 FOUNDATION，没有任何排除

`GitWorkspaceAdapter.requireSafeRelativePath`（:225-246）只拒绝越界路径（绝对路径、盘符、`..`），
**不排除 dotfile**；`ls-tree -r -t -l` 的列举结果同样包含它们。

实测（只读探针，见 §6）：把典型凭据路径逐个喂给
`RepositoryPathClassifier.classify` + `RepositoryCandidateLane.of`：

```text
路径                                    materialKind      候选组
.env                                    CONFIGURATION     FOUNDATION
.env.local                              OTHER             FOUNDATION
config/.env.production                  OTHER             FOUNDATION
.npmrc                                  CONFIGURATION     FOUNDATION
.pypirc                                 OTHER             FOUNDATION
.netrc                                  OTHER             FOUNDATION
.aws/credentials                        OTHER             FOUNDATION
server.pem                              OTHER             FOUNDATION
private.key                             OTHER             FOUNDATION
keystore.p12                            OTHER             FOUNDATION
app.jks                                 OTHER             FOUNDATION
id_rsa                                  OTHER             FOUNDATION
id_rsa.pub                              OTHER             FOUNDATION
deploy/service-account.json             DEPLOYMENT        FOUNDATION
src/main/resources/application.yml      CONFIGURATION     FOUNDATION
src/main/resources/application.properties CONFIGURATION   FOUNDATION
src/main/java/com/x/Api.java            SOURCE_CODE       SCOUT_SOURCE
docker-compose.yml                      DEPLOYMENT        FOUNDATION
```

**结论（实测，不是推断）：**

```text
1. 上面每一个凭据文件都是 FOUNDATION 材料 → 会被 Foundation 通道按材料类别轮转选中
   → 会被读取 → 会原样进入送往 DeepSeek 的请求。**当前没有任何一层拒绝它们。**
   （.env / .npmrc 是被**当作可分析配置**主动支持的；其余落入 OTHER 后同样进 FOUNDATION，
     因为「非源码材料 → FOUNDATION」。）
2. 注意 .env 的识别是**后缀**匹配：.env.local / .env.production 不是 CONFIGURATION，
   但仍然是 FOUNDATION——按名字排除时不能只写 ".env"。
3. 最常见的口令位置 application.yml / application.properties 同样是 FOUNDATION，
   且对分析价值很高 —— 这决定了「按路径整份排除」不能作为唯一手段。
```

即：**当前的失败路径不需要任何特殊构造。**把一个含 `.env` 的普通仓库指给
DelveForge，一次成功的分析就可能把它的内容送给第三方 Provider。

### 2.2 二进制内容会以文本形式进来

`GitCommandResult.stdoutText()`（:429-431）用 `new String(stdout, UTF_8)` **宽松解码**：
非 UTF-8 / 二进制 blob 会带上替换字符被当成文本返回，因此也可能进入材料。
对二进制凭据（`.p12` / `.jks`）而言，内容层面已无从识别——只有路径能识别。
而 §2.1 的表显示 `.p12` / `.jks` 同样在 FOUNDATION 里。

## 3. 送往外部 Provider 的全部调用点

生产代码里 `AiGateway.generate` 一共 6 处，逐一看是否承载仓库内容：

| 调用点 | 载荷 | 承载仓库文件内容 |
|---|---|---|
| `RepositoryAnalysisExtraction.java:114` | 材料（路径 + 内容） | **是 —— 唯一一处** |
| `RepositoryScoutExtraction.java:132` | 文件描述符（编号/路径/大小/语言/类别/角色提示） | 否 |
| `RepositoryRegionScoutExtraction.java:161` | 目录描述符（前缀/计数/语言/角色提示） | 否 |
| `ProfileExtraction.java:126` | 用户本轮输入 + 候选 Profile | 否（用户数据） |
| `ProfileSufficiencyEvaluator.java:111` | 候选 User Profile | 否（用户数据） |
| `DirectionDiscoveryExtraction.java:157` | Confirmed User Profile + RepositoryProfile 文本 + Evidence | 否**直接**，但是仓库内容的**下游**（见 §5） |

两条 Scout 的载荷形状可在代码中核对：

```text
FileCatalogPayload.render           :58-80    reference / path / sizeBytes / language / materialKind / roleHints
RepositoryRegionScoutExtraction.describeCatalog :198-215  reference / pathPrefix / 计数 / languages / roleHints
```

两者都不含任何文件内容，只有描述符。**Region Scout 与 File Scout 从不接收文件内容**——
这一点在上一次变动的实现与测试里都已成立，本任务未改动它们。

## 4. 当前是否会把原始内容写入日志或持久化

### 4.1 日志

生产代码中的 logger 只有两处（`backend/.../src/main` 全量扫描）：

```text
1. RepositoryUnderstanding.logAggregates          :298-315
   一行聚合：result / scoutPath / 各通道选中数 / 读到数 / 两类跳过数
   不含路径、不含内容、不含异常文本（有测试断言不含 pom.xml / src/main/App.java）

2. ApiExceptionHandler 的各 handler
   记录 loggedRoute（路由模板，不是原始 URI）+ describe(Throwable)
   describe 只输出**异常类型链 + 栈位置**，不含 message / cause message / toString（:373-407）

基础设施两个 Adapter（GitWorkspaceAdapter / DeepSeekAiGatewayAdapter）
    两者都**没有 Logger**（grep Logger 计数 = 0）
```

已有的两类风险路径由配置与 ADR-0002 覆盖，本任务不重新设计：

```text
- Framework DEBUG（spring web / tomcat / coyote）已在 application.yml 固定为 INFO
- MyBatis DEBUG 会打印 SQL 与绑定参数 → application.yml 已注明不要整体打开
  com.ayywl.delveforge=DEBUG，并点出 persistence 包的风险
- 异常文本一律不落日志（ADR-0002）
```

**未发现把仓库内容写进日志的代码路径。** 也**未发现**把材料写盘的地方（生产代码中没有
对材料的文件写入；`WorkspaceMutationPort.writeFile` 不在 Repository Analysis 的依赖里）。

### 4.2 持久化

只有**模型输出**被持久化，原始内容不落库：

```text
V4__repository_profile.sql
    repository_profile.purpose                        TEXT
    repository_profile_section_item.(section, value)  TEXT     ← techStack / modules / capabilities / …
    repository_profile_evidence.(source_type, source_ref, claim)
```

`source_ref` 受校验（`RepositoryAnalysisExtraction.requireEvidenceRefersToSentFiles` :138-152）：
必须等于本次真正交给模型的文件路径，因此它只能是材料中已存在的路径，不可能是内容。

**原始内容本身在当前实现里既不入库、也不写日志；能被模型复述的内容才可能间接落库。**

## 5. 下游传播：仓库内容能走多远

```text
RepositoryProfile（purpose / techStack / modules / capabilities / reusableAssets
                   / limitations / risks）+ Evidence（source_ref / claim）
        ↓
SQLite（V4 表）
        ↓
HTTP 响应（RepositoryProfileController → 上述字段）
        ↓
DirectionDiscoveryExtraction.describeRepositoryProfile   :210-232
    purpose / techStack / modules / capabilities / reusableAssets / limitations / risks
    + describeEvidence(...) 的 sourceRef 与 claim
        → 再次进入 AiGateway（Direction Discovery 的 Prompt）
```

**结论：**模型如果复述了它看到过的内容，那段文本会依次出现在 Profile 字段、SQLite、
API 响应，以及**下一次**发给 Provider 的 Prompt 里。也就是说，同一条信息在一次分析之后
会反复出机器，而不止一次。

这决定了 §6 的一个关键判断：**净化必须发生在第一次出机器之前**；只要第一次请求里没有，
后续所有环节都不可能凭空出现（模型没有见过它）。

## 6. 证据来源与未验证部分

### 6.1 只读探针（§2.1 那张表）

用一个不进入代码库的探针直接调用已编译的分类器，逐个打印
`classify(path).materialKind()` 与 `RepositoryCandidateLane.of(entry)`：

```text
方式    在 target/ 下的一次性 main 类，只调用 RepositoryPathClassifier 与
        RepositoryCandidateLane 两个纯函数；不构造 Map、不读文件、不调用 AI
        （与上一次分层侦察用的探针同一手法）
影响    不修改任何生产代码；探针产物位于 git-ignored 的 target/ 下
```

它记录的是**分类器的实际行为**，不是从注释或 ADR 推断的意图。

### 6.2 未验证 / 不构成证据的部分

```text
1. 未在真实仓库上统计规则命中率（FP / FN）——那需要先有实现，属于 10B-2 之后的验证。
2. 未逐条核对 Spring / RestClient 在所有日志级别下的请求体行为；当前依据是
   application.yml 的 logger 配置与既有验证记录，不是本次重新测得。
3. 未验证 DeepSeek 侧如何保留/使用请求内容——超出本项目可控范围，只有契约层面的假设。
4. 未实测「Foundation 通道在真实仓库上选中 .env 的概率」。可以确定的是它**在候选组里**、
   上限与轮转规则允许它被选中（§1 的读取链路）；具体选中率需要在真实仓库上观察。
5. RepositoryAnalysisMaterialCollector（M1 的确定性选材）同样调用 readFile（:165），
   但它在生产装配里**不存在**（无 Bean；RepositoryAnalysisWiringTest 有断言）。
   它是一条当前不生效、但保留在代码库里的内容入口——若将来被重新接入，
   ADR-0006 的边界同样必须覆盖它（边界位置见 ADR-0006 §决策）。
```

## 7. 由本侦察直接推出的三条设计约束

```text
C1  边界必须同时覆盖 Foundation 与定向源码——它们在 material 里已经合流，
    但合流点是「读取之后、请求之前」，因此存在一个天然的单点。
C2  不能只靠路径排除：最可能含口令的 application.yml / .properties 是重要分析材料，
    整份排除会明显损害分析价值。
C3  也不能只靠内容规则：.p12 / .jks 这类二进制凭据在文本层面无从识别，
    只能靠路径识别；而宽松解码会让它们的字节变成无意义的替换字符。
```
