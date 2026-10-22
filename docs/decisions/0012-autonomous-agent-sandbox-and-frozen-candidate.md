# ADR-0012: Autonomous Agent Sandbox and Frozen Candidate

Status: Accepted for Task 1 implementation; runtime acceptance is recorded separately.

Date: 2026-10-21

用户本次 Task 1 明确替代旧 M4/M5 的一次性 CodingProposal 执行设计。旧文档中的冻结范围、
不使用 Spring AI、直接在 WorkingCopy 修改和单独 Repair/Retry 授权不约束新 Agent 路径。
这不是对旧流程恢复安全边界的放宽。

## Decision

- Domain 维护 AgentRun 与不可变 Candidate 事实；Application 拥有逐轮模型调用与工具调度。
- Infrastructure 使用发布的 Spring AI 1.1.x，关闭内部工具执行；保留 M1–M3 的文本 AiGateway。
- 每次 Run 使用独立 Docker Linux 容器和源副本，正式 WorkingCopy 只读提供可信 A。
- Maven 由镜像提供。执行容器无网络；依赖准备容器只能通过内部网络访问受控 Central 镜像代理。
  代理只取得受信任上游 artifact，并维护共享缓存；项目只能写每 Run 私有 Maven repository。
- Candidate 是实际源文件形成的 A→B diff，必须以独立 index 重放得到相同 B 后事务性保存到 SQLite。
- Run 成功只说明 Candidate 已冻结；不改变 Step 状态、WorkingCopy revision 或正式 Git commit。
- 活跃 Run 的持久化占用阻止重复启动及旧执行流冲突。重启不恢复对话，先处理残留资源再释放占用。
- 2026-10-22 验收修正：进入 FINALIZING 时原子保存材料保留意图，失败终态及再次重启均保持；
  只有成功冻结或明确 NO_CHANGES 解除，释放占用不能抹除保留事实。V23 向前修复旧版仍占用的最终化截面。
- 同 Step / baseline 已成功冻结后，预约事务拒绝新编码 Run，保留既有 Candidate 供 Task 2 使用。
- 所有可执行项目代码的容器显式清空 HTTP/HTTPS/FTP/ALL/NO_PROXY 大小写变量；可信 Central 代理的出站配置独立。

## Alternatives and trade-offs

继续旧 proposal 流程不能实现文件工具驱动的探索、测试与修复。直接修改 WorkingCopy 不能满足
候选生成与 Delivery 分离。容器内再建 Agent 服务会复制编排职责。全量源 Bundle 会重复存储。
Docker 增加运行前提；内部网络与代理增加少量基础设施，但允许冷缓存和 POM 新依赖而不开放任意出站。
第一版只支持有界文本源、受控 JDK 镜像和公开 Central 依赖；不承诺任意 Git/filter/JDK/私库兼容。

## Task 2

Delivery 消费已冻结 Candidate，不重跑 Agent。正式应用、接受验证、Git/SQLite Delivery 恢复、
旧 UI/路由清理和 SSE 均不属于 Task 1。
