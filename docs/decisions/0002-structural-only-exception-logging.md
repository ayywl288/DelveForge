
# ADR-0002: Structural-Only Exception Logging to Prevent Runtime Data Leakage

Status: Accepted

Date: 2026-09-15

## Context

`AGENTS.md` §8.8 规定日志中不得记录 API Key / Token / Credential 等敏感信息，
且该要求不因日志级别改变。

M0 建立统一错误处理与日志基础时发现：

```text
异常 message 与 cause message 可能嵌入请求内容、用户数据、
Provider / SDK 返回内容或凭据。

而应用在记录异常之前，无法可靠判断任意自由文本
是否包含敏感运行时数据。
```

因此，常见的：

```java
log.error("operation failed", exception);
```

虽然诊断能力很强，但会把异常 message、cause message
以及第三方异常携带的自由文本直接交给日志系统。

实现过程中还确认了第二类泄漏路径：

```text
应用自己的异常日志并不是唯一来源。

部分 Framework / Library logger 会自行记录 exception.toString()、
请求信息或其他运行时上下文。

例如 M0 实测发现：
ExceptionHandlerExceptionResolver 在 DEBUG 下能够输出
包含 exception message 的 "Resolved [...]" 日志。

这类日志发生在 @RestControllerAdvice 控制之外。
```

因此真正需要解决的问题是：

> 在异常自由文本可能包含敏感数据、
> 又无法可靠预判其内容的情况下，
> DelveForge 自己的异常日志应该记录什么，
> 以及如何降低 Framework / Library 自行记录运行时敏感文本的风险？

本决策在实现过程中经历了两次修正：

```text
v1
记录完整异常与原因链
→ 可能把凭据写入日志

v2
根据异常来源分类，只记录 DelveForge 自有异常 message
→ 仍依赖未来 Adapter 作者如何构造 message，
  不能形成稳定的安全保证
```

## Options Considered

### Option A — 记录完整异常与 Cause

Pros:

- 诊断信息最完整
- 符合 Java 项目的常见日志习惯

Cons:

- exception message 与 cause message 可能直接承载运行时敏感数据
- 无法保证第三方 SDK 或未来 Adapter 不把 Provider 返回内容写入 message
- 与 `AGENTS.md` §8.8 的敏感信息约束冲突

### Option B — 根据异常来源决定是否记录 message

例如：

```text
DelveForge 自有异常
→ 可以记录 message

第三方异常
→ 只记录类型
```

Pros:

- 可以保留一部分业务语义
- 诊断体验优于完全删除 message

Cons:

- 安全性依赖异常作者未来始终保持 message 安全
- 只要出现：

  exception("failed: " + responseBody)

  这种写法，来源分类就失效
- “异常属于谁”无法证明“异常文本是否安全”

### Option C — DelveForge 自身只记录结构化异常信息，不记录自由文本

当前选择。

记录：

```text
exception type information
selected stack location information
operation / path / result 等显式结构化字段
```

不记录：

```text
exception message
cause message
Throwable.toString()
完整 Throwable 对象
```

Pros:

- DelveForge 自己控制的异常日志路径不再依赖自由文本内容判断
- 不需要猜测“这段 message 是否安全”
- 可以通过测试锁定行为

Cons:

- 丢失异常 message 所携带的大量诊断信息
- 某些故障的根因判断会明显变困难

### Option D — 对异常文本进行正则或启发式脱敏后记录

Pros:

- 能保留大部分 message
- 诊断体验接近 Option A

Cons:

- Secret / Token / 用户数据的格式不可能完整枚举
- 新 Provider 或新 SDK 可能引入新的敏感内容形式
- 容易产生“已经经过脱敏，所以日志安全”的错误信心
- 不能作为确定性的安全边界

### Option E — 完整记录日志，但通过日志加密或访问控制降低风险

Pros:

- 保留完整诊断能力

Cons:

- 不解决应用主动把敏感信息写入日志这一事实
- 对当前 local-first 单用户应用增加明显基础设施成本
- 与 M0 的实际复杂度不匹配

## Decision

### DelveForge-controlled exception logging

DelveForge 自己控制的异常日志路径采用 structural-only strategy。

日志可以记录：

```text
operation
path / resource identifier（仅在确认本身不敏感时）
result
exception type information
selected stack location information
其他经过明确设计的安全结构化字段
```

不得直接记录：

```text
exception.getMessage()
cause.getMessage()
Throwable.toString()
Throwable / cause object 作为 logger 参数
未经明确安全设计的 Provider / SDK / HTTP response 文本
```

该规则不因 DEBUG / INFO / WARN / ERROR 等日志级别改变。

如果需要记录更具体的失败原因，
应由相关 Adapter 或组件显式提取和设计
**确定语义、确定字段、经过安全判断的 structured diagnostic information**，
而不是重新允许任意 exception message。

### Framework / Library logging

只控制 DelveForge 自己的 Logger 不足以完成安全目标。

对于已经确认可能在 DEBUG / TRACE 等级输出
异常自由文本、请求上下文、Authorization 信息
或其他敏感运行时数据的 Framework / Library logger，
应用配置必须限制其日志级别。

具体 logger package / category 列表属于运行配置，
由 `application.yml` 维护，不在本 ADR 中固定为永久名单。

每次引入新的 Provider SDK、HTTP client、Git library、
AI framework 或其他重要技术组件时，
都必须重新确认其低级别日志行为。

## Rationale

选择 Option C 而不是 A，是因为完整 Throwable 日志的安全性依赖：

> “异常自由文本恰好不含敏感数据。”

这个前提无法由日志层可靠验证。

选择 Option C 而不是 B，是因为异常的来源不等价于异常文本的安全性。

DelveForge 自己的 Adapter 同样可能无意中把：

```text
response body
request content
provider error
credential-related data
```

拼入 message。

因此，“这是自己的异常”不能成为记录 message 的依据。

选择 Option C 而不是 D，是因为启发式脱敏可以作为辅助措施，
但不能成为该安全属性成立的基础。

本项目优先消除 DelveForge 自己异常日志路径中
对任意自由文本的依赖，而不是试图识别所有可能的秘密格式。

Framework / Library logger 需要单独限制，
因为 M0 的实际验证已经证明：

> 即使应用自身完全不记录 exception message，
> Framework 仍可能在应用异常处理逻辑之外自行输出它。

因此本决策包含两部分：

```text
应用自身：
不记录任意异常自由文本

已知第三方路径：
通过 logger configuration 降低其自行输出敏感运行时文本的风险
```

## Consequences

### Positive

- DelveForge 自己控制的 exception logging path
  不会通过 `Throwable` message / cause message
  直接把任意运行时自由文本写入日志
- 不依赖正则识别所有 Secret 格式
- 规则可以通过自动化测试验证：
  在 message 与 cause message 中放入测试凭据，
  并断言 DelveForge 自身日志输出中不存在这些文本
- 异常类型、发生位置以及显式结构化 operation context 仍然可以用于定位问题
- Future Adapter 可以通过明确设计的 safe structured fields
  增强诊断能力，而无需退回完整异常日志

### Negative

- **诊断能力明显下降。**

  很多第三方异常最有价值的信息只存在于 message 中，
  当前策略会主动舍弃这些内容。

- 对已经发生且无法稳定复现的问题，
  缺少 exception message 可能显著增加定位成本。

- 临时开启 DEBUG **不会自动恢复 exception message**。

  这是本决策有意保留的性质。
  DEBUG 可能提供更多经过允许的上下文，
  但不能绕过 structural-only exception logging policy。

- 如果需要额外诊断信息，
  Adapter 作者需要显式设计安全的结构化字段；
  当前没有机制自动保证所有 Adapter 都提供充分诊断信息。

- 某些经过精心设计、实际上完全安全的 exception message
  也会被一并舍弃。

## Risks

- **本决策不能保证整个进程中的所有日志绝不出现敏感数据。**

  DelveForge 可以确定性控制自己的 exception logging path，
  但第三方 Framework / Library 拥有独立的日志行为。

  当前 logger level 配置只能覆盖已经识别的风险路径，
  不是未来所有依赖的永久完整清单。

- 新增依赖可能引入新的 DEBUG / TRACE 泄漏路径。

- 当前策略尚未经过真实 AI Provider Credential 与 Provider Error Response 的完整验证。

- 如果未来为了排障临时增加日志，
  开发者可能绕过本 ADR，重新记录 Throwable 或 message。

- 如果日志未来离开本机，
  例如进入日志采集系统、远程上报或由用户主动提交，
  威胁模型将明显扩大。

## Verification Expectations

至少应保留能够验证以下行为的测试：

```text
1. exception message 包含测试 Secret
   → DelveForge-controlled log 不出现 Secret

2. cause message 包含测试 Secret
   → DelveForge-controlled log 不出现 Secret

3. 日志仍包含预期的 exception type / safe structural diagnostics

4. 默认日志配置不会因为 root level 调整，
   自动重新打开已知高风险 Framework logger
```

这些测试验证的是：

> 当前已识别并受项目控制的日志路径。

它们不应被解释成：

> 已经证明未来所有第三方依赖都不可能泄漏任何敏感信息。

## Revisit Conditions

```text
首次接入真实 AI Provider
  → 验证 Adapter 错误映射方式
  → 验证 Provider SDK / Spring AI 等组件的 DEBUG / TRACE 行为
  → 确认 Credential、Provider Response 等不会通过日志路径泄漏


诊断成本变得不可接受
  → 正式重新评估本 ADR

  优先评估：
  safe diagnostic code
  structured provider status
  operation id
  明确定义的安全字段

  而不是直接恢复任意 exception message


引入新的重要技术组件
（HTTP client / Git library / Spring AI / Provider SDK 等）
  → 检查其低级别日志输出
  → 必要时更新 application.yml 中的 logger 配置


日志需要离开本机
（采集 / 上报 / 用户提交日志文件）
  → 重新评估数据分类、日志保留与访问控制策略
```

## References

```text
AGENTS.md
  §8.7 Error Handling
  §8.8 Logging

docs/ARCHITECTURE.md
  §6.2 Cross-Cutting Infrastructure

application.yml
  当前实际 Framework / Library logger level 配置
```
