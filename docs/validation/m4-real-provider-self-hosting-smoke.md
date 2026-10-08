# M4 — Real-Provider Self-Hosting Smoke

**Status:** Validation Record / **Not Source of Truth**

**Date:** 2026-10-08（UTC+08:00）

**Verdict:** **M4 REAL SMOKE: BLOCKED**

**Implementation / source revision A:** `610719dbde14009f0edc02a721a8ca91e812e9de`

本次按执行请求先检查强制安全门槛，随后在应用启动和凭据注入之前停止。
没有调用真实 Provider，没有执行 M1→M4 API 链路，不是一次已完成的演化验证。
本文仅记录实际检查结果，不新增架构规则，不修改 M4 范围或冻结里程碑。

## 1. 环境、制品与隔离检查

- 原 SoftwareAsset 目标：`E:\develop\DelveForge`。
- 起始 HEAD 为 A，工作树干净，无 assume-unchanged / skip-worktree 标志。
- JDK：`E:\develop\jdks\jdk-21.0.12.1`。
- 已有制品候选：`backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar`。
  它来自报告预算修正的完整验证，包含新报告预算配置；本次只读取，没有重建、复制或启动。
- 制品 SHA-256：`4c8ed2110dbc2017c195558935dbb6e1193619fe4e14b5f583036621d5c60b52`。
- 制品入口：`org.springframework.boot.loader.launch.JarLauncher`，
  Start-Class 为 `com.ayywl.delveforge.app.DelveForgeApplication`。
- 尚未建立独立运行目录、数据库、受控 Workspace 或运行日志。
- 用户提供的凭据没有注入进程、写入文件或用于网络请求；当前进程未配置 Provider key 环境变量。

本次请求明确要求：不可信构建 / 测试子进程不能继承 Provider 凭据或访问无关私有文件；
若无法建立隔离，应停止，不得先执行后补安全证据。

### 实际检查结果

1. `WorkspaceCommandRunner.java:43–54` 使用普通 `ProcessBuilder` 启动子进程，
   设置工作目录并过滤部分继承环境，没有在此处切换独立用户或提供文件访问沙箱。
2. `WorkspaceCommandRunner.java:102–112` 的环境过滤针对 Git / Maven / JVM 注入变量；
   没有移除 `DEEPSEEK_API_KEY` 或 DeepSeek 配置环境变量。
   `application.yml:116` 默认通过 `DEEPSEEK_API_KEY` 提供凭据。
   因此以该环境变量启动当前应用，会让构建子进程继承凭据；本次没有实际注入凭据来复现。
3. 切换工作目录、使用独立 Git clone 及有界命令并不限制该进程的系统文件读取权限。
   当前没有已建立并验证的执行沙箱证明能够阻止无关私有文件访问。
4. Docker CLI 已安装，但本地 Docker Linux Engine 的命名管道不存在；
   在沙箱外只读检查也得到相同结果。Docker Desktop 已安装但未运行。
5. 沙箱外 `wsl --list --quiet` 仅列出 `docker-desktop`；没有可直接使用的普通 Linux 分发。
   未发现 Podman、bwrap、firejail 或 Windows Sandbox 命令。

没有启动或安装容器服务、下载镜像、修改系统隔离配置或构造凭据转发替代路径。
仅启动 Docker 服务也不会自动改变当前应用的子进程环境继承与权限边界。
现有环境未建立请求要求的隔离，因此本次在安全门槛处停止。

## 2. API 链路与模型输出

Provider 调用次数为 **0**，业务 API 请求次数为 **0**。

未创建 UserProfile、SoftwareAsset、RepositoryProfile、ProductDirection、EvolutionPlan 或 WorkingCopy。
没有生成 Step、CodingProposal、选材结果、ExecutionResult 或 VerificationResult。
没有手工编辑 SQLite、替换模型输出或改写 Criteria / Preconditions。

原候选目标仍是改进既有 RepositoryPathClassifier 的下划线文件名角色提示并添加回归测试；
它没有进入真实规划，也没有被声明为模型已生成的 Step。

## 3. 生命周期与 revision

源仓库在检查前及报告编写前都保持 HEAD = A、工作树干净。
没有运行 WorkingCopy provisioning、mutation、build 或 test。
没有 Candidate B 或 A→B diff，没有三次持久化检查点。
Step 状态及 baseline/current/lastVerified revision 均不适用，因为本次没有创建相关领域对象。

没有执行 Repair、Retry、Rollback 或 Recovery，也没有推进任何软件信任。
本次后续唯一允许的仓库变化是提交本报告；报告提交改变文档和源 HEAD，
不能被计为 Evolution Candidate 或演化成功。生产代码、测试、配置、schema 及 canonical docs 保持 A 的内容。

## 4. 验证证据与已解除的预算障碍

本次没有运行 Maven；不能把历史自动化验证计为本次真实烟雾测试的 Verification。

预算修正的既有证据为：聚焦 57 项通过，完整 `mvnw.cmd verify` 1308 项通过，
0 failures / errors / skipped，耗时 8:01。完整套件产生 120 份报告，累计 XML 5,332,959 字节；
新回归已用真实离线 Maven 证明 120 份报告可以在合适预算内被接受，超限失败关闭。

原定 smoke 外部覆盖值如下；本次没有启动应用或实际应用这些参数：

```text
--delveforge.workspace.verification.max-test-reports=160
--delveforge.workspace.verification.action-timeout=900s
--delveforge.workspace.verification.invocation-timeout=1200s
--delveforge.workspace.verification.max-output-bytes=8388608
```

报告数量及可配置预算已不是本次停止原因。没有跳过、筛选或将部分测试冒充全套测试。

## 5. 结果、限制与后续门槛

**M4 REAL SMOKE: BLOCKED**，阻塞类别为本次请求的运行隔离未建立。

这是当前执行方式的安全边界与环境准备限制，不是模型输出质量失败；
也没有观察到 M4 Proposal、mutation、生命周期或验证接受逻辑在真实运行中的正确性失败，
因为这些阶段都未执行。Git / WorkingCopy 隔离不能被误称为不可信进程的权限沙箱。

后续重新执行前，需具备并验证一个仅允许访问目标快照、授权缓存与本次 smoke 数据的执行环境，
同时使 Provider 凭据位于构建子进程不可见的边界外。应使用无秘密的环境哨兵与文件访问探针证明隔离，
不能只换参数名或把 key 放入构建仍能读取的配置文件。
本报告不决定具体隔离架构，也不授权修改生产执行机制、扩展验证能力或实现 M5。

M4 仍保持 IN_PROGRESS；没有开始复盘。
