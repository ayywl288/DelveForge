# Agent 上下文压缩与 token 预算

基于本地 `main` 的 `adf3c1b` 实现。原有 `codex-migration/`、`frontend/design/`、`grilling/` 保留。

## 历史摘要

每个旧工具结果按调用 ID 关联同条 Assistant 消息中的调用。摘要用 Jackson 输出一行一个 JSON 对象，带一次 `COMPACTED` 前缀。重复压缩时会合并前一期记录，旧版记录里的正文摘录也会移除。默认摘要上限 12,000 字符。摘要按三档分配空间。平台反馈、失败工具结果及 `edit_file`、`exec_command` 结果为高优先级；成功的查询、读取、列表、日志及其他普通工具元数据为普通优先级；未验证的 Agent 自述在有余量时保留。同档先选较新的完整记录，最终按观察时间倒序展示。每条记录都受摘要字符上限约束。

| 工具 | 保留的数据 |
| --- | --- |
| `list_files` | 请求路径、偏移、页大小，文件和目录路径、总数、下一页位置。兼容字符串路径和带 `path` 的对象。 |
| `search_code` | 查询与搜索路径、匹配路径及行号、截断标记和结果分类。 |
| `read_file` | 请求路径与行范围，实际读取范围、后续页位置和分类。 |
| `edit_file` | 请求路径、变化路径、冲突分类和原因、修改是否开始及纠正指令。 |
| `exec_command` | 净化并限制长度的命令、工作目录和超时，退出码、超时、取消、源码变化、快照、执行及日志引用、实际报告的测试计数。缺失计数保持缺失。 |
| `read_log` | 执行 ID、日志引用、流、读取范围、分页、截断及丢弃标记。 |
| `update_plan` | 每次请求单独注入最新 `workingNotes`，旧计划回复不进入摘要。 |

源码、编辑前后文本、搜索摘录、标准输出、标准错误和日志正文从旧记录中移除。较近的完整交互继续保留原始消息。平台纠正带 `platform_feedback`，Agent 的文字说明带 `agent_statement` 和未验证归因。两者均经过净化和长度限制。

以下是多调用回归样例中两条记录的内容。

```json
{"kind":"tool","tool":"search_code","ok":true,"callId":"find","classification":"RESULT","arguments":{"path":"src","query":"next"},"matches":[{"path":"src/A","line":7}],"truncated":true}
{"kind":"platform_feedback","text":"Completion rejected; previous response discarded."}
```

## 输入与传输预算

Application 从同一份消息列表与同一份工具定义计算输入估计并调用模型。输入包括系统指令、已确认 Step、环境、交互、摘要、最新笔记、工具说明及 JSON Schema。Infrastructure 按 V4.1 的非思考文本模板处理连续用户消息、DSML 调用及其参数、按调用顺序归位的工具结果和生成前缀。模板控制符计为单个特殊 token；普通文本由移除 added vocabulary 的本地分词器编码。单例分词器串行保护本机调用和关闭操作。

| 默认项目 | Tokens |
| --- | ---: |
| 模型窗口 | 1,000,000 |
| Agent 输出预留 | 8,192 |
| 可用输入 | 991,808 |
| 压缩触发点（向上取整，90%） | 892,628 |
| 压缩目标（向下取整，75%） | 743,856 |

压缩从保留最近六个完整交互组开始，每次删减一组都会重新计算实际候选输入。固定指令、已确认授权、环境及当前笔记都参与计算；固定输入超出可用容量时返回 `CONTEXT_LIMIT`。单字段、工具和模型回复的既有字符上限继续有效。Candidate、Delivery、取消、可信版本及状态机沿用现有路径。

HTTP 层对添加 `thinking.type=disabled` 后实际序列化的字节数组检查 `max-request-bytes`，默认 8 MiB。超限在网络提交前返回 `REQUEST_BYTES_LIMIT`。Agent 在同一轮压缩、重算、再调用一次；第二次超限保留该错误。这个重试位于工具分派之前。

## 分词器与参考结果

使用 Infrastructure 专属的 DJL Hugging Face Tokenizers **0.40.0**。其 Java 接口和随包提供的 CPU 原生库负责 BPE 编码，项目只维护当前请求所需的模板规则。加载时选择 CPU 实现，避免具备 GPU 的机器触发 DJL 下载。运行时需要 DJL 支持的平台及可写的原生库缓存。模型资源、模板文本和两份 MIT 许可随应用分发；Python 绑定只用于生成固定样例。官方模型表将 `deepseek-flash` 映射为 DeepSeek-V4.1-Flash-1017，列出的上下文长度为 1M。[模型文档](https://api-docs.deepseek.com/quick_start/pricing/)

- 模型仓库修订为 `1bae0e1088993b9b82de08f59de5be5a34e42170`。[固定分词器](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash-1017/blob/1bae0e1088993b9b82de08f59de5be5a34e42170/tokenizer.json)
- 启动时校验 `tokenizer.json` 的 SHA-256 为 `c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b`。未匹配配置模型会明确失败。
- 期望值由官方 `deepseek-recipe==0.2.0` 的 `ChatCompletionRequest.convert` 与 `DeepseekV41Encoding.encode` 生成。输入和预期长度均保存在测试资源中。[参考编码实现](https://docs.rs/crate/deepseek-recipe-encoding/0.2.0/source/src/v4/)

| 固定输入 | 官方参考 | Java |
| --- | ---: | ---: |
| 英文请求 | 9 | 9 |
| 中文系统与用户消息 | 19 | 19 |
| 源码和多角色消息 | 22 | 22 |
| 用户输入中的字面控制符 | 31 | 31 |
| 多角色、两次工具调用、乱序返回、两份 schema 和最新笔记 | 417 | 417 |
| 无初始系统消息的工具定义 | 305 | 305 |
| 长源码 | 964 | 964 |
| 含数值、嵌套数组与转义字符的 JSON 参数 | 374 | 374 |

8 组样例在禁用网络、使用空 DJL 缓存的 Linux 容器中全部通过。现有运行诊断保存 `estimatedPromptTokens`、响应的 `promptTokens` 和 `promptTokenDifference`（响应值减估计值）。本机 HTTP 测试对英文样例得到估计 **9**，模拟响应报告 **20** 个输入 tokens，差值 **+11**。提供者重试后，最后响应的用量仍可比较，Run 累计用量保留未知。本任务未调用付费模型，未运行真实用户项目，线上估算偏差尚未测量。若服务商以后更换 `deepseek-flash` 指向的模型或请求模板，需重新核对资源与样例。

## 优先级修正前的工程验证

| 验证 | 测试总数 | 实际通过 | 失败 / 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| Windows 开发过程全量 `mvnw.cmd verify` | 1,985 | 1,930 | 0 / 0 | 55 |
| Windows 当时版本定向 Maven 验证及打包 | 131 | 131 | 0 / 0 | 0 |
| Linux 当时版本断网全量 `./mvnw -o verify` | 1,986 | 1,931 | 0 / 0 | 55 |

Windows 定向运行选择 `AgentContextTest`、`AgentContextConvergenceTest`、`AgentToolVisibilityTest`、`DeepSeekTokenCounterAdapterTest`、`CodingAgentConfigurationTest`、`SpringAiAgentModelTest` 与 `CodingAgentServiceTest`。该次 Linux 快照的 36 个后端修改文件与工作区逐字节相同，完整 reactor 通过并生成 Spring Boot 包。Infrastructure JAR 内核对到分词器、模板、许可证和归属声明；应用包内含 DJL Tokenizers 0.40.0。

该次 Linux 各模块的总数 / 跳过数为 Domain 209 / 0、Application 927 / 0、Infrastructure 621 / 52、App 229 / 3。付费模型与真实用户项目 Agent Run 未执行。

首次额外的 Linux 全量运行使用 Git 2.43；它拒绝仓库现有的 `--no-lazy-fetch` 选项，导致 `GitWorkingCopyTreeDiffIntegrationTest` 有 5 项失败。仅在一次性验证容器中换用 Git 2.56 后，该类 8 项通过，最终完整 reactor 也通过。开发过程中小摘要淘汰旧编辑记录的问题已通过优先保留决策相关记录修正；旧命令记录使用 `kind` 时仍会剔除历史日志摘录。

## 最终复核与回归

`AgentContextConvergenceTest` 新增两项回归。第一项让平台纠正、成功编辑、命令收据、失败日志读取、搜索位置和文件行范围，与大量列表结果及冗长自述竞争 1,600 字符。旧逻辑在此用例中保留了冗长自述，1 项运行、1 项失败。修正后，测试核对同档较新记录优先、跨档展示仍按时间倒序、正文排除、重复压缩去重，以及最近六组完整调用的顺序。第二项使用 1,024 字符，验证余量足够时保留最新自述。

本轮 Windows 离线上下文定向测试 11 项全部通过；Domain 与 Application 模块合计 1,138 项全部通过（209 + 929），失败、错误、跳过均为 0。两次验证的计数各自独立。

最终源码在断网 Linux 容器中使用 Maven Wrapper 运行完整 `verify` 并成功打包。共 1,988 项，实际通过 1,933 项，失败 0，错误 0，跳过 55 项。各模块总数 / 跳过数为 Domain 209 / 0、Application 929 / 0、Infrastructure 621 / 52、App 229 / 3。本次未调用付费模型，也未运行真实用户项目。

## 改动文件

| 范围 | 文件 |
| --- | --- |
| Application | `CodingAgentService.java`、`AgentTokenCounter.java`、`AgentContextBudget.java` |
| Infrastructure | `SpringAiAgentModel.java`、`DeepSeekProperties.java`、`DeepSeekTokenCounterAdapter.java`、`DeepSeekPromptRenderer.java`、Infrastructure 的 `pom.xml` |
| 资源与装配 | `CodingAgentConfiguration.java`、`application.yml`、`.gitattributes`、`deepseek-v4.1-flash-1017/{tokenizer.json,tool-instructions.txt,LICENSE,RECIPE-LICENSE,NOTICE.txt}` |
| 上下文、配置与模型回归 | `AgentContextTest.java`、`AgentContextConvergenceTest.java`、`AgentToolVisibilityTest.java`、`CodingAgentConfigurationTest.java`、`AgentModelLoggingPolicyTest.java`、`SpringAiAgentModelTest.java`、`DeepSeekTokenCounterAdapterTest.java`、`CodingAgentServiceTest.java`、`DeepSeekAiGatewayAdapterTest.java`、`deepseek-recipe-0.2.0.json` |
| 测试装配及构造调用 | `TestAgentServices.java`、`CodingAgentLiveTest.java`、`SpringAiAgentModelLiveTest.java`、`RepositoryInputExecutionTest.java`、`ReadToolRecoveryDockerTest.java`、`PatchConflictOrchestrationDockerTest.java`、`NativeFrontendDeliverySmokeTest.java`、`GenericCommandDeliveryTest.java`、`FullStackAgentSmokeTest.java`、`CommandUsabilityDockerTest.java`、`DockerRetentionRecoveryTest.java` |
| 文档 | `docs/ARCHITECTURE.md`、`docs/LOCAL_SETUP.md` 及本报告 |
