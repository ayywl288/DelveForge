# Step 03 完成报告引用调查与修复

最终 Maven verify 有一条与保留源码及视图一致的成功收据。历史验证器重放时接受了这条引用。该 Run 的终态为 FAILED / NO_PROGRESS，具体被拒的引用列表无法从旧事件还原。修复针对平台给模型的反馈、重复判定及审计记录。

## 现场与版本

工作树为 `main` / `c273d96` 加既有未提交改动。以 SQLite `mode=ro` 和 `PRAGMA query_only=on` 查询指定 Run，读取了其命令收据和 partial 元数据。没有打开或改写 `partial.diff`，没有访问目标仓库、重启服务、操作 Docker 或调用模型提供者。

- Run `7f002e93-6e83-4894-9b94-55b62f7151ca` 的本地时间为 2026-11-08 20:49:03 至 21:03:52（UTC−08:00）。数据库记录 113 次模型调用、132 次工具调用，retained=1、occupied=0。Candidate 与 Delivery 表中均为 0 条。
- SANDBOX_READY 记录了 JDK 21 和镜像 `sha256:c5fa23a578976c80685ed5d88a7b91489de4ea8a46e3b2c2d6bd74f577b7b1ab`。保留下来的后端 JAR 为 `0.1.0-SNAPSHOT`，其修改时间早于 Run 开始，SHA-256 为 `7721be3271d6561366894755e8ed2d70d1a50291e7e102aa2df9d5009661bf68`。
- 该 JAR 中 CompletionEvidence、CodingAgentService、DockerAgentSandbox 的类字节分别与修复前独立副本中的已编译类逐字节哈希一致。三者 SHA-256 前缀分别为 `b39a055df205`、`ec643971502d`、`afb9a8927d8a`。JAR 包含 v8 事实校验、旧拒绝文本和 `completion_check` 路径，运行收据含 `progressDiagnosticDigest`，与此实现吻合。Run 表未保存后端 JAR 哈希或 Git 提交，无法独立证明当时 JVM 加载的每个字节。

## 可确立的失败链

| 事件序号 | 已核实事实 |
| --- | --- |
| 6923 | 较早的 verify `f6a14721-4309-467c-9ee0-76fb35739f63` 返回 0、BUILD SUCCESS，报告含 53 个 PASSED。前后快照均为 `3f6ffe8652ee861b4f9efa0867f5f6a5131e61371cf945cec37e382076f797fc`。 |
| 6936、6951、6961、6971 | README.md 的编辑均记录 PATCH_APPLIED。 |
| 6981 | 最后一次 verify `7babaa2f-4902-4e84-9360-5f654da7618b` 返回 0、BUILD SUCCESS，报告含 53 个 PASSED。前后快照均为 `82058d6fa303652e927d0ab28078c59aa2f70cd06df8ff348f33451053bbe0e3`，与 partial 元数据完全相同。 |
| 6986 | 最后一次 WORKING_NOTES 的 7 项均记为 completed。这是 Agent 的工作笔记。 |
| 6991、6999 | 两条 COMPLETION_CHECK_REJECTED 均为 UNRESOLVED_CHECK_FAILURE，原因均为 `Referenced execution failed or is source/view stale`。两者之间 6995 还有一次完成报告格式纠正。 |
| 7000–7005 | 模型请求与用量记录之后，出现 UNVERIFIED_WORK_RETAINED、FAILED、RESOURCES_RELEASED。终止原因为 NO_PROGRESS。 |

39 条命令收据均为 CAPTURED，视图均为 FULL，三个资源计数均为 0。有 2 条收据与保留源码前后快照和视图全部匹配，其中另一条 `55a1bd83-bbb2-479a-a498-cbb2ac755673` 没有测试报告。旧 verify 对应的是 README 修改之前的源码。

活动路径经过 `CodingAgentService.loop` 解析报告、`DockerAgentSandbox.completionFeedback` 读取当前快照、组装 evidenceVersion 8 的全部 checks，再由 `AgentCheckEvidence.requireAcceptable` 路由到 `CompletionEvidence`。它按 invocationId 在同 Run 查找收据，并要求 snapshot、afterSnapshot 和整个 sourceView 均吻合。通过后仍需进入 FINALIZING、停止写入、冻结源码、持久化 Candidate，再由 Delivery 独立核验。

在旧 v8 代码中，上述泛化消息对应已找到收据的源码或视图不匹配。未知或重复引用走另一条拒绝路径。当前有效的失败执行可以作为失败事实被引用。旧平台的问题是丢失了被拒 ID、具体哪一项身份不匹配，以及可供纠正的当前收据。所有完成检查又都以空参数 `{}` 和相同反馈进入进展保护。重复达到停止门槛时先抛异常，最后一次拒绝来不及持久化。这一顺序解释了终态前只有两条持久化拒绝的现象。

没有保存原始被拒报告，也没有保存每次拒绝时的具体快照。可能引用了较早的 verify、其他旧调用，或同时引用当前调用与旧调用。现有记录无法区分这些情况，也无法证明当时只提交了最后一次 verify 的 ID。

## 两次假设重放

独立目录 `completion-reference-replay` 中的 `ReferenceReplay.java` 和 `replay.json` 仅用于本机调查。重放从记录中提取 39 条身份、结果、测试计数和工具事件的 outputTruncated，省略源码、命令及日志正文，以保留元数据的快照/视图作为最终身份。两次报告均为合成输入。

| 手工选择的引用 | 保留 JAR 中的验证器 | 修正后的验证器 |
| --- | --- | --- |
| 较早 verify | UNRESOLVED_CHECK_FAILURE，泛化原因 | STALE_SOURCE，指出第 0 项、引用 ID、执行前后与最终身份，列出可核对的当前收据，包括带 53 项测试的最终 verify |
| 最终 verify | 接受 | 接受 |

重放仅运行 Java 验证器。Windows 下使用：

```powershell
java -cp 'packaged/*' ReferenceReplay.java
java -cp '<独立副本>/backend/delveforge-application/target/classes;packaged/*' ReferenceReplay.java
```

## 实现

- 引用问题按类型返回安全诊断。已知执行保留退出码、超时、取消与测试计数；引用字段只展示规范 UUID，其他输入以位置和受控占位符标识。源码身份及视图计数取自收据与最终快照。当前收据列表最多 6 条，并报告匹配总数，优先展示有实际测试且未报告失败的当前收据。Agent 自行选择，平台没有改写其报告。
- 完成检查比较引用集合、重复项与当前身份。调整说明、引用顺序或仅改变先报告哪项错误不会规避重复保护。新的实质引用可以继续纠正。第二次重复有警告，触发停止的拒绝也先持久化。事件记录引用总数、最多 8 个提交项、具体错误位置与原因，不保存原始被拒报告、命令或日志正文。
- Run 界面识别已记录的完成报告循环。缺少停止原因明细的历史记录使用一般重复提示。v8 最终测试统计同时按源码和执行视图筛选，历史执行仍进入历史统计。
- v8 的 executions 仍可省略；当前失败收据仍可作为失败事实使用。v7 仍要求成功验证，v1–v6 路由未修改。上述行为符合 ADR-0026。没有更改 Step 授权、源码隔离、冻结、partial 的资格或独立 Delivery。

## 本轮文件

```text
backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/CompletionEvidence.java
backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/CompletionReferenceFailure.java
backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentProgress.java
backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/CodingAgentService.java
backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/FactualCompletionEvidenceTest.java
backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/agent/AgentProgressTest.java
backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/DockerAgentSandbox.java
backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CodingAgentServiceTest.java
frontend/src/components/agentEventPresentation.ts
frontend/src/components/AgentActivityRow.vue
frontend/src/components/agentResultPresentation.ts
frontend/src/components/AgentTestEvidence.vue
frontend/src/views/EvolutionView.vue
frontend/tests/agent-timeline.test.ts
frontend/tests/quick-start.test.ts
docs/validation/completion-reference-rejection-20261108.md
```

## 验证结果与边界

在独立后端副本和独立前端副本运行：

```powershell
$env:JAVA_HOME='E:\develop\jdks\jdk-21.0.12.1'
$env:DELVEFORGE_AGENT_DOCKER='false'
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am '-Dtest=FactualCompletionEvidenceTest,CompletionEvidenceTest,AgentCheckEvidenceTest,BaselineCheckComparisonTest,AgentProgressTest,CodingAgentServiceTest,CandidateDeliveryTest#unresolvedTestFailureBlocksDeliveryWithoutChangingTrust' '-Dsurefire.failIfNoSpecifiedTests=false' verify
pnpm test tests/agent-timeline.test.ts tests/agent-result-presentation.test.ts tests/agent-result-components.test.ts tests/quick-start.test.ts
pnpm build
```

最终后端 137 项通过，0 失败、0 错误、0 跳过，受影响模块构建成功。应用层 45 项，基础设施层 92 项。前端 4 个测试文件共 136 项通过，类型检查与构建成功。

合成服务测试使用真实 SQLite、临时 Git 原仓库和独立副本。通过沙箱 Port 夹具提供固定收据并调用生产事实校验。纠正成功的路径从反馈获得已有 ID，随后经过正常的 finish 调用；持续错误的路径保留 3 条拒绝事件、最后的停止标记和临时 Git diff，原仓库 HEAD、状态及 README 均未变化。此处的沙箱执行、partial 捕获与成功 finish 使用夹具，未验证真实容器的完整冻结或崩溃恢复。独立 Delivery 另有回归覆盖。未运行付费模型、真实 Step 或全量端到端验收，也未部署修正。

## 已保留的真实 partial

元数据将它标为 `UNVERIFIED_NON_DELIVERABLE`，列出 18 个路径，基线为 `95c32076e5bdf0991967aa09197c94eb1afe4927`，记录的 SHA-256 为 `2d350acf8583db01b051a25fc1b13a07f28a2b3ee2c9d0103d28a30b8ef03a8e`。内容哈希此前已按用户说明验证，本轮没有读取补丁正文重新计算。

可以保留原件供审阅。若另行授权复用，可在匹配基线的临时副本中核对补丁副本的哈希和适用性，审阅差异并验证重建的源码。正式交付仍需受授权的执行产生冻结 Candidate 并经过独立 Delivery。本次没有应用、删除或提升该 partial，也没有将历史测试结果移用于新的源码。
