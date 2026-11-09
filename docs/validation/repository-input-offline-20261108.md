# 选定仓库输入与离线执行验证（2026-11-08）

## 范围与实现

基于本地 `main` / `c273d96`，落实 [ADR-0027](../decisions/0027-selected-repository-input-and-offline-execution.md)。
原有未跟踪的 `codex-migration/`、`frontend/design/`、`grilling/` 未修改。未提交、推送或运行真实用户项目。

- 删除选定仓库文件按秘密形态、配置键名、环境/密钥扩展名的能力降级。分析选材、Git 导入、容器投影、文件枚举/读取/搜索/编辑、Shell、冻结与独立 Delivery 使用一致资格。
- 文件工具提供准确原文；日志与分析材料保留尽力脱敏。拒绝把脱敏占位符新增到源码，避免掩码污染交付。运行确认处说明所选材料可能进入模型、命令和日志，没有逐文件审批。
- 保留控制目录、路径/链接、资源预算、宿主隔离、冻结树重放及独立 Delivery 校验。`.git/.hg/.svn/.ssh/.aws/.gnupg/.m2` 是明确路径例外，提交到选定快照也会被准入检查显式拒绝。
- 依赖网络的普通 Docker 失败/超时、旧 Engine 缺少隔离网络能力时，固定该 Run 使用 `--network none`。归属、隔离、取消、停止失败仍拒绝。能力报告给模型和界面，区分离线与已启动但未验证上游下载的受控代理。
- 命令后捕获失败仍记录实际 invocation、退出码、日志、执行/捕获状态及失败原因，不自动重放，也不纳入有效验证记录。
- SQLite 在进入 RUNNING 的事务中先记录 retained。停止写入并确认持久终态后才能清理；partial 捕获失败保留原始卷，停止/持久化失败保留占用，重启协调遵循相同顺序。原始材料不是 Candidate。
- 进展比较保留输出及完整净化输出的摘要；忽略耗时、调用/日志身份。重复短循环第二次提示、第三次终止，源码变更仍重置。
- 证据版本仍为 v8，历史 v1–v7 兼容、NO_CHANGES、源码版本关联和已有全树扫描不变。容器协议升为 v10，默认镜像标签/构建脚本同步。

## 验证环境与命令

Windows / JDK `21.0.12.1`，项目 Maven Wrapper、pnpm，Docker Linux Engine API `1.54`。
没有调用付费模型。模型端使用确定性回复；Docker、Git、SQLite 和命令执行使用真实实现。

测试镜像 `delveforge-agent:repository-input-v10-test` 由本机现存 `delveforge-agent:final-workflow-cpp-20261108` 加入当前四个 Python 工具脚本构建；
构建使用 `--network none --pull=false`，不是生产镜像构建或上游下载验收。

```powershell
$env:JAVA_HOME='E:\develop\jdks\jdk-21.0.12.1'
.\mvnw.cmd verify

$env:DELVEFORGE_AGENT_DOCKER='true'
$env:DELVEFORGE_AGENT_IMAGE_21='delveforge-agent:repository-input-v10-test'
.\mvnw.cmd -q -pl backend/delveforge-infrastructure -am '-Dtest=RepositoryInputExecutionTest,GenericCommandDeliveryTest,CodingAgentServiceTest,DockerPatchPolicyTest,RepositoryContentPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 完整执行后的补跑与全模块构建：修正的四项断言、相关 Docker 用例、全部 app 测试。
$env:DELVEFORGE_REAL_STEP='false'
$env:DELVEFORGE_TASK2_E2E='false'
$taskAppTests = @(rg --files backend/delveforge-app/src/test -g '*Test.java' | ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) })
$taskSelectedTests = @('CandidateDeliveryTest#independentDeliveryAcceptsSelectedConfigurationUnderTheSamePolicy','RepositoryCompatibilityIntegrationTest#selectedContentCannotBypassGitTransformOrReservedDirectoryRules','RepositoryInputExecutionTest','DockerAgentSandboxTest#selectedResourcesRemainReadableEditableAndPresentInFullSourceView+configurationReferencesAndSyntheticDefaultsFreezeWithoutContentBasedRejection+sourceFixturesAndDefaultsRemainEditableThroughFreeze+finalCaptureAcceptsDefaultMutationOutsideTheEditingTool+selectedSourceWithTokenShapeRemainsAvailableInExecution+frontendAuthenticationFixtureHasExactSourceReadsAndSanitizedCommandOutput+selectedConfigIsReadableEditableAndOutputRemainsBounded') + $taskAppTests
.\mvnw.cmd verify "-Dtest=$($taskSelectedTests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd verify '-Dtest=DelveForgeApplicationTests' '-Dsurefire.failIfNoSpecifiedTests=false'

docker run --rm --network none --read-only --tmpfs /tmp:rw,nosuid,size=128m --cap-drop ALL --security-opt no-new-privileges --mount 'type=bind,source=E:\develop\DelveForge\backend,target=/backend,readonly' delveforge-agent:repository-input-v10-test python3 -m unittest discover -s /backend/delveforge-infrastructure/src/test/python

$env:DELVEFORGE_SANDBOX_PROTOCOL_IMAGE='delveforge-agent:repository-input-v10-test'
python -m unittest discover -s backend/delveforge-infrastructure/src/test/python -p test_sandbox_image_protocol.py

pnpm --dir frontend test
pnpm --dir frontend build
git diff --check
```

## 结果

完整后端执行（`task-final-verify.log`）：Domain 209、Application 908 全部通过；Infrastructure 604 项中 549 通过、4 失败、51 条件跳过，因此该次根 `verify` 失败且尚未运行 app 模块。
4 项均为任务涉及的测试断言：3 项配置交付成功后把 Windows 检出 CRLF 与 LF 文本直接比较，改为校验准确 Git 对象内容并独立验证可信工作树；另 1 项仍断言 `.npmrc` 路径必须被排除，改为允许并保留 `.ssh` 与 Git 内容转换拒绝断言。没有为此修改生产行为。
补跑（`task-final-targeted.log`）基础设施 13 项全部通过：上述 4 项、真实 Docker 内容策略 7 项、离线配置交付/下载与捕获失败 2 项；app 的 225 项中 222 通过、3 条件跳过，0 失败/错误。所有模块构建与 Spring Boot 打包成功。
独立应用启动测试（`task-application-smoke.log`）另有 3 项全部通过，最终全模块构建再次成功。至此 app 共 228 项，3 条件跳过。
这些是分段回归与修正后补跑的结果；没有将先前失败的无筛选根 `verify` 写成成功，也没有重复已通过的其他用例。

- 定向 Java：139 项通过，0 失败/错误/跳过。包含 `CodingAgentServiceTest` 85、`DockerPatchPolicyTest` 37、`RepositoryContentPolicyTest` 12，以及真实 Docker/Git/SQLite 的通用命令交付 3、选定输入/离线执行 2。

- 前端：23 个文件、359 项测试通过；`build` 包含 `vue-tsc -b`，类型检查与构建通过。
- Linux Python：61 项中 54 项通过、7 项可选镜像测试跳过；随后单独启用该镜像的 7 项测试全部通过。
- `git diff --check` 与镜像准备脚本的 PowerShell 语法检查通过。
- Windows 上直接执行全部 Python 测试曾遇到两项创建符号链接权限错误；未削弱测试，改在 Linux 容器验证真实链接行为。
- 首轮后端暴露与新产品决定冲突的旧断言；已更新对应读取/编辑/交付断言，保留完整性与隔离断言。中途旧编译版本的全量执行被停止，不能作为最终通过结果。

## 证据边界与下一步

合成离线配置任务覆盖已有 properties 默认值、已有环境示例、新建环境文件和 YAML，经文件工具与 Shell 修改、冻结到 v8 Candidate，再由独立 Delivery 更新正式 WorkingCopy；原始资产保持不变。
真实断网下载返回非零退出码及错误输出；真实命令写文件并制造非法链接后捕获失败，保留实际退出码 7、日志与源卷。
SQLite 故障注入覆盖保留意图写入失败、终态写入失败、部分捕获失败、停止失败与重启协调。它证明这些受控故障路径，不等于断电恢复或所有平台故障的完备证明。

代码及合成证据已可进入真实模型端到端验证；运行前仍需为目标 JDK 构建并配置 v10 镜像，旧镜像明确拒绝。本次只验证了本地 JDK 21 合成镜像，没有重建/部署正式 JDK 17/21 镜像。
合成测试通过不能证明真实模型能完成任意仓库，付费模型与真实目标项目的端到端验收未运行。
网络能力由每次 Run 报告，受控代理启动不保证任何依赖下载成功。原始失败 Run 未重跑，目标项目、测试及环境未在真实 Run 中被人工修改。
二进制/文件预算、保留控制路径和未支持的仓库类型仍可能阻止运行或交付；失败保留卷占用空间，现有 retained 机制不提供自动恢复对话。
