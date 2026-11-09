# Coding Agent 思考模式与启动上下文

## 请求及计数

`deepseek-flash` 对应 DeepSeek-V4.1-Flash-1017，模型表列出的最大输出为 384K。Agent 默认发送 `thinking.type=enabled`、`reasoning_effort=high`、`max_tokens=65536`。关闭思考时只发送 `thinking.type=disabled`。工具请求使用 Spring AI 1.1.8 的 `DeepSeekApi.chatCompletionEntity`，无自动工具回调。工具结果按调用 ID 与顺序回传。错误响应正文不进入日志。[模型规格](https://api-docs.deepseek.com/quick_start/pricing/) · [Chat Completions 思考与工具协议](https://api-docs.deepseek.com/guides/thinking_mode/)

1,000,000 的窗口预留 65,536 后有 934,464 个输入 token。90% 触发点为 841,018，75% 目标为 700,848。估计包括固定指令、启动上下文、工具定义、历史思考与结果以及当轮笔记。旧组被移除时，摘要不包含其思考。实际 HTTP 字节仍有独立的 8 MiB 上限。

参考样本使用已有的 `deepseek-recipe==0.2.0` 和仓库固定词表。用该版本的 Python 运行测试资源中的 `generate-thinking-fixtures.py <tokenizer.json 路径>` 可重新生成 JSON。思考模式、工具与无工具历史的五组期望计数依次为 **485、485、36、33、43**。原有非思考样本保持原值。[官方参考编码](https://docs.rs/crate/deepseek-recipe-encoding/0.2.0/source/src/v4/dsv41.rs)

## 启动上下文

方向及 Plan 身份来自启动时验证的 `ExecutionPreparationState.Loaded`。画像使用该方向的 `(userProfileId, revision)` 从版本锚点和内容行读出六区。没有快照时标记 `available=false`。仓库快照按 Plan 引用的 ID 查找并核对资产及方向引用，只补充分析版本和技术栈。当前文件事实仍以可信仓库和实际工具读取为准。

模型看到已接受 Plan 的当前状态、复用能力、风险和有序 Step 概览。仅 `authorizedStep` 含修改范围与计划变更。背景描述每项最多 600 个 UTF-16 单元，每个描述列表最多 16 项，截断和省略均有标识。Step 概览保留前 128 项以及当前授权 Step，异常长 Plan 会记录省略项数。原始对话、Evidence 和其他 Step 的变更列表不进入固定上下文。

## 验证结果

各次运行分别计数。Linux 完整验证覆盖最终后端源码，模块顺序为 Domain、Application、Infrastructure、App，各模块测试总数为 209、929、628、229，跳过数为 0、0、52、3。Windows 早一轮完整验证之后增加了一个协议测试，最终定向验证重新编译并打包本地应用。

| 运行 | 总数 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Windows 最终源码定向 verify 与打包 | 180 | 180 | 0 | 0 | 0 |
| Linux 最终源码断网完整 verify 与打包 | 1995 | 1940 | 0 | 0 | 55 |
| Windows 开发期间完整 verify 与打包 | 1994 | 1939 | 0 | 0 | 55 |
| Windows 启动上下文与模式定向回归 | 176 | 176 | 0 | 0 | 0 |
| 开发中：Windows 本地克隆管道无权限 | 144 | 142 | 2 | 0 | 0 |
| 开发中：旧测试仍断言不得出现画像 | 144 | 143 | 1 | 0 | 0 |
| 首次 Linux 全量：快照遗漏根目录 mvnw.cmd | 1766 | 1713 | 0 | 1 | 52 |

HTTP 回归检查了实际发出的 JSON，包括多工具顺序、连续两轮调用、空可见内容、完成纠正、请求重试、缺失思考字段及截断响应。SQLite 测试覆盖指定画像 ID 与版本、版本缺失、已保存空版本和六区映射。压缩测试检查最近组保留原思考内容，早期摘要不含该内容。普通测试使用本地响应；真实模型质量没有实测。

## 付费 smoke test

实时 DeepSeek 验证 **NOT RUN**。以下步骤会调用付费接口，仅在已授权并已向当前 PowerShell 会话安全提供 `DEEPSEEK_API_KEY` 后执行。从仓库根目录运行以下命令。

```powershell
$env:DELVEFORGE_AGENT_LIVE = 'true'
try {
    .\mvnw.cmd -pl backend/delveforge-infrastructure -am "-Dtest=SpringAiAgentModelLiveTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
} finally {
    Remove-Item Env:DELVEFORGE_AGENT_LIVE
}
```

该测试使用模拟文件结果。预期为三次连续模型回复，前两次各返回一次 `read_file`，并带可重放的思考字段。第二次读取的文件名来自第一次工具结果，最后的可见回答包含两个返回值。检查 Surefire 对此类报告 1 项通过、0 跳过。无需启用产品 Run、Docker 或保存请求／响应正文。

## 修改文件

- **delveforge-app · 实现**　[CodingAgentConfiguration.java](../../backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/config/CodingAgentConfiguration.java)、[application.yml](../../backend/delveforge-app/src/main/resources/application.yml)
- **delveforge-app · 测试**　[AgentModelLoggingPolicyTest.java](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/config/AgentModelLoggingPolicyTest.java)、[CodingAgentConfigurationTest.java](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/config/CodingAgentConfigurationTest.java)
- **delveforge-application · 实现**　[AgentModelFailure.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentModelFailure.java)、[AgentProtocol.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentProtocol.java)、[AgentStartupContext.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentStartupContext.java)、[CodingAgentService.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/CodingAgentService.java)、[ExecutionPreparationState.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/evolution/preparation/ExecutionPreparationState.java)、[AiRequest.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/ai/AiRequest.java)、[UserProfileRepository.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/persistence/UserProfileRepository.java)、[ProfileExtraction.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileExtraction.java)、[ProfileSufficiencyEvaluator.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java)
- **delveforge-application · 测试**　[AgentContextConvergenceTest.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/AgentContextConvergenceTest.java)、[AgentContextTest.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/AgentContextTest.java)、[AgentToolVisibilityTest.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/AgentToolVisibilityTest.java)、[InMemoryUserProfileRepository.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/shared/InMemoryUserProfileRepository.java)
- **delveforge-infrastructure · 实现**　[DeepSeekPromptRenderer.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/DeepSeekPromptRenderer.java)、[DeepSeekTokenCounterAdapter.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/DeepSeekTokenCounterAdapter.java)、[SpringAiAgentModel.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/SpringAiAgentModel.java)、[DeepSeekAiGatewayAdapter.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/ai/DeepSeekAiGatewayAdapter.java)、[SqliteUserProfileRepository.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteUserProfileRepository.java)
- **delveforge-infrastructure · 测试**　[CandidateDeliveryTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CandidateDeliveryTest.java)、[CodingAgentLiveTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CodingAgentLiveTest.java)、[CodingAgentServiceTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CodingAgentServiceTest.java)、[DeepSeekTokenCounterAdapterTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/DeepSeekTokenCounterAdapterTest.java)、[SpringAiAgentModelLiveTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/SpringAiAgentModelLiveTest.java)、[SpringAiAgentModelTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/SpringAiAgentModelTest.java)、[TestAgentServices.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/TestAgentServices.java)、[DeepSeekAiGatewayAdapterTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/ai/DeepSeekAiGatewayAdapterTest.java)、[SqliteUserProfileRepositoryIntegrationTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteUserProfileRepositoryIntegrationTest.java)、[deepseek-thinking-0.2.0.json](../../backend/delveforge-infrastructure/src/test/resources/deepseek-thinking-0.2.0.json)、[generate-thinking-fixtures.py](../../backend/delveforge-infrastructure/src/test/resources/generate-thinking-fixtures.py)
- **项目文档**　[ARCHITECTURE.md](../../docs/ARCHITECTURE.md)、[LOCAL_SETUP.md](../../docs/LOCAL_SETUP.md)、[0015-executable-agent-steps-and-incomplete-response-recovery.md](../../docs/decisions/0015-executable-agent-steps-and-incomplete-response-recovery.md)、[agent-context-budget-20261109.md](../../docs/validation/agent-context-budget-20261109.md)、[agent-thinking-context-20261109.md](../../docs/validation/agent-thinking-context-20261109.md)
