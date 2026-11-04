# 原随机全栈演化续跑：目标完整性与安全预检

日期：2026-11-04，Asia/Shanghai。工程分支 `codex/random-fullstack-lifecycle-fix`，起点 `198f388`；本轮不合并、不推送。

> 本文保留当时的安全阻塞结论。后续明确授权分离执行与模型可见性，并进行了真实编码续跑；
> 最新结果见[源码保护修正与真实试验](source-protection-execution-20261104.md)，旧失败和用量不改写。

## 当前结论

**BLOCKED BY A CONFIRMED EXTERNAL/SECURITY REQUIREMENT。**

真实 Docker 已在固定源与原正式副本上证实：修复 Java 运算符误判后仍有 10 个检查输入受保护，
sourceView 为 PROJECTED，后端与前端检查均不可用，预检 ENVIRONMENT_BLOCKED。
不能在当前安全决策下宣称完整产品演化成功；没有 Candidate 或 Delivery。

本轮沿用原随机结果、既有领域记录和全栈 Step，没有重抽、换 commit、重新生成画像或重新规划。
已通过正常产品 API 准备官方 WorkingCopy 并确认 Step；随后修正试验环境的 owner/cache 配对错误。
真实 Run 在首个模型请求之前因执行视图缺失受保护输入而失败。
同时定位并修复了 DelveForge 凭据规则把 Java 三元运算字符串操作数当成属性赋值的真实误判。

## 固定试验与完整性

- 上游：[校园设备预约系统](https://github.com/Techd81/Campus-laboratory-equipment-reservation-system-based-on-Spring-Boot-Vue/tree/307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05)，MIT。
- commit `307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05`；tree `8f05690251b35365a5a5d60a5ddcd5d99bc38ec2`。
- 360 个已跟踪文件；原始抽样、种子和历史用量见[原试验记录](final-random-fullstack-20261104.md)及其抽样清单。
- Plan `7bc1be8e-d40a-4df4-9c45-fb83fe97c932`；Step `adb5edec-22a3-426f-bcbd-8ecf2563938d`。
- WorkingCopy `c9086c3b-f5d7-4ec7-89dc-fd7f5218d794`。
- 原源：`data/random-fullstack-20261104/source/`；正式副本：该目录下 `workspaces/<WorkingCopy ID>/`。
- 功能仍为预约提交前的规则预检、失败原因解释，以及后端规则评估与 Vue 的连接；不是兼容性演示用的小改动。

Codex 没有修改第三方源、pom、manifest、锁文件、测试、SQL、Vue 配置或 Git 历史，也没有修改正式 WorkingCopy 源码。
本轮真实 Coding Agent 未进入模型循环，因而没有 Agent 产生的功能补丁。
原源和正式副本分别保留 Git HEAD/tree/status、360 个物理文件 SHA-256 和 index SHA-256；最终前后比较见下方证据。
最终逐项比较全部相同：两份副本各 360 文件、Git index 摘要均未变化，HEAD/tree 仍为上述固定值，Git status 均为空。
试验 SQLite 仅通过正常产品 API 写入；工程诊断使用只读连接，没有直接修补状态。

## 工作副本恢复与环境错误

### 官方 WorkingCopy 准备

保留历史受控 clone 失败。相同 JAR、同一试验数据库、同一固定源改用标准 Windows 原生启动环境后，
原 Plan 的正常 prepare API 返回 200（26.031 秒），Plan 激活并绑定正式副本；随后正常 Step confirmation 返回 200。
准备身份 A=P=固定 commit，currentRevision=lastVerifiedRevision=A，工作副本 READY。
恢复没有依赖 DelveForge 代码改动或目标修补。

历史 clone 的叶子 stderr 没有保留，不能反向声称已证明某个具体 Git 错误。
当前证据只证明标准启动上下文下同一次官方准备能力正常，未复现原失败；不以猜测增加 Git 重试或放行分支。

### RESOURCE_OWNERSHIP：试验配置错误

| 项目 | 失败时 | 修正后 |
| --- | --- | --- |
| `DELVEFORGE_AGENT_OWNER` | `df-random-fullstack-fix-20261104` | `delveforge-local-agent` |
| `DELVEFORGE_MAVEN_CACHE` | `delveforge-maven-central-cache` | 同一默认共享缓存 |
| 缓存原有 owner 标签 | `delveforge-local-agent` | 保持原值 |
| 缓存原有 run 标签 | `shared-cache` | 保持原值 |

首个 Run `8b546160-4029-4f92-bc48-b986a691515c` 在 `DockerAgentSandbox.requireOwner()` 拒绝，
FAILED / RESOURCE_OWNERSHIP，modelCalls=0、toolCalls=0、tokens=0、occupied=0，无 Candidate。
这是工程操作员的隔离 owner 与默认缓存不匹配，不是 DelveForge 所有权缺陷。
仅改运行环境，不改 `ensureVolume`、`requireOwner` 或资源标签；没有接管、重建或删除共享缓存。

改回一致配置后的 Run `de6a3c53-6b3c-4167-88e8-89c431efdf27` 正常产生 SANDBOX_READY：
JDK17，2 GiB / 2 CPU / pids 256，network=none，rootfs readonly。
使用镜像 `delveforge-agent:preflight-v8-final2-jdk17`，
ID `sha256:04e620c63aaa21697baf9162f982569dc79b36415dd83aaa9d5954f4b050f20c`。
完整 Step 同时选择 `backend`、`frontend`，没有隐藏其中一端。

随后真实 Preflight 返回 ENVIRONMENT_BLOCKED，诊断：

```text
INVALID_ARGUMENTS
Executable source or build configuration is withheld; no checks can attest this view
```

该 Run 同样 0 模型、0 工具、0 Token；没有把缺失输入改称源码编译失败或 READY_BASELINE_FAILURE。
失败 Run 和事件完整保留；retained=false 表示运行资源已正常释放，不表示历史记录被删除。

## 已修复的 DelveForge 缺陷

`DeterministicRepositorySecretPolicy.mutationProtectedLines()` 的直接凭据正则会把下列封闭 Java 语法误认为属性：

```java
record.setAction(temporary ? "generated-password" : "provided-password");
```

冒号在这里属于三元运算，左侧字符串是操作数，不是凭据字段名。
原目标的 `backend/src/main/java/com/lab/reservation/service/impl/UserServiceImpl.java:152` 命中同一机制。
工程测试使用独立合成字符串，不复制目标值、不改目标文件。

修复仅在 `.java` 的确定代码位置识别紧邻 `?` 的完整引号操作数。
注释、字符串、含 Unicode 转义或未解释文本块的文件、其他语言继续保守处理；已知密钥格式仍独立命中。
旧 M1–M3 净化规则保持。没有测试目录豁免、项目名称白名单、新依赖、模块变化、领域状态变化或镜像改版。

| 验证 | 实际结果 |
| --- | --- |
| 修复前运行新增回归 | 56 项，3 failure、0 error/skip；新增三个三元字符串操作数案例失败 |
| 首次修复后定向三组策略测试 | 90 项，0 failure/error/skip |
| 最终收窄为 Java 后相同三组 | 91 项，0 failure/error/skip |
| 最后增加 Unicode 转义保守分支后 | 相同三组 92 项；在当前 Domain/Application 全部 1082 项中执行，0 failure/error/skip |
| 内容能力集成回归 | 新增：Java 操作数属于 SOURCE；直接凭据测试仍 PRESERVED_ONLY / blocksChecks |
| 完整后端 verify（Java-only 版，加入最后 Unicode 保守判断前） | 总数 1871，52 跳过，实际执行 1819；0 failure/error，BUILD SUCCESS；含真实 Docker 探针 |
| 当前源内容策略集成与真实 Docker 预检 | 8 项，0 failure/error/skip；真实探针 ENVIRONMENT_BLOCKED，withheldSourceCount=10 |
| 当前源全模块 package -DskipTests | BUILD SUCCESS；此命令不执行测试 |

完整命令为 Maven Wrapper `verify -DargLine=-Xmx512m`，MAVEN_OPTS=-Xmx384m，
显式开启 `DELVEFORGE_SELECTED_SECURITY_PREFLIGHT=true` 并指定上述两个源路径、固定 revision、backend/frontend。
最后的 Unicode 保守分支仅收紧 Application 内容识别：重新执行当前 Domain/Application 全部测试，
再执行 infrastructure 的 `RepositoryContentPolicyTest,SelectedRepositorySecurityPreflightLiveTest` 并全模块打包。
上述各轮有重叠，不能相加为独立测试数。
没有启用真实 Provider 测试。DelveForge 前端未改动，本轮未重新运行其前端检查；历史 308 项不混入本轮计数。

## 安全边界及未执行阶段

修复前保护清单包含 11 个检查输入；Java 误判消除后还剩 10 个：
9 个测试输入含直接 password/token 字面量，另有 `frontend/src/api/paths.ts` 的 password 字符串属性。
后者属于当前保守字段匹配，不声称它实际泄露了生产密码；本报告也不把测试值当作已证明有效的生产凭据。
即便单独处理该路径常量，其他 9 个测试输入仍触发已接受的保护边界。

[ADR-0020](../decisions/0020-repository-content-capabilities.md) 明确保护硬编码示例口令，
被保留的源码、测试或构建描述不允许从缩减视图产生检查 PASS。
因此不能把“测试里的值看起来是假的”当作放行依据，也不能删测试、把原字节送入容器或手工改源来消除阻塞。
这次 Java 运算符歧义可以在当前规则内修复；放行直接凭据字面量需要另行改变安全决策，不能伪装为兼容性补丁。

真实无模型适配器探针直接使用修复后的策略和原正式副本；不创建业务 Run，不伪装成 Agent 成功。
探针的 PASS 只表示安全拒绝和资源清理符合预期；目标环境仍是 BLOCKED。
实际 sourceView、预检诊断、容器身份与完整性结果见结构化证据。

| 产品阶段 | 真实状态 |
| --- | --- |
| 分析 / 画像 / 方向 / 规划 | 历史已完成，复用六次真实响应，没有重跑 |
| 官方 WorkingCopy 准备 | 本轮正常 API 成功，READY |
| 原 Step 确认 | 本轮正常 API 成功，READY；Plan ACTIVE |
| Environment Preflight | 本轮真实 Run ENVIRONMENT_BLOCKED；修复后真实 Docker 适配器探针仍 ENVIRONMENT_BLOCKED，withheldSourceCount=10 |
| Coding Agent Loop | 未进入，0 新模型请求 |
| 目标 Maven 检查 | 未执行，不能标为测试失败或通过 |
| 目标前端检查 | 未执行，不能标为测试失败或通过 |
| Candidate 冻结 | 未执行，无 ID |
| Delivery | 未执行，无 ID / 交付 revision |
| 预约规则预检业务验收 | 未执行，功能没有实现证据 |

继续条件不满足源于受保护输入和检查完整性，不源于编码次数或 Token 数。
本轮没有新增人为累计预算上限，也没有在已知环境阻塞下启动付费重试循环。
MySQL 等目标业务运行前提尚未验证；不能因为尚未走到这些阶段就宣称全部兼容。

## 用量与证据

| 范围 | 实际用量 |
| --- | --- |
| 历史六个模型响应 | prompt 79,445 + completion 21,650 = total 101,095；reasoning 15,678 包含在 completion 内 |
| 本轮两个失败业务 Run | 新请求 0，Token 0，各自失败原因独立保存 |
| 修复后真实 Docker 探针 | 请求 0，Token 0；无模型实例或模型替身 |

本地原始材料保留于 `data/random-fullstack-20261104/`：
`fix-api-evidence.json`、`fix-domain-state.json`、两次失败日志与第二次完整事件观察、
`fix-domain-state-final.json`、`fix-run-events-final.json`、`fix-preparation-proof.json`、
`fix-integrity-before.json` / `fix-integrity-after.json` / `fix-integrity-result.json`、`fix-resources-before.json` / `fix-resources-after.json`。
验证日志在 `target/random-fullstack-fix-20261104/`；真实探针 JSON 在 infrastructure 的 `target/`。
这些忽略目录仅列本地证据位置；[可审阅的脱敏摘要](random-fullstack-resumption-evidence-20261104.json)
随本报告提交，含日志摘要、实际跳过数、容器身份、10 个受保护输入路径及文件摘要，不包含凭据或目标源代码。

最终资源核对一致：共享缓存 owner/run 标签、两个当前 Agent 镜像 ID、三个其他项目容器的身份与状态均保持。
真实探针的私有资源已正常释放；没有全局清理或触碰其他数据库。正式试验服务已停止，没有活动编码 Run。

## 工程合并建议

工程提交 `10c36c8d9600015b47393361ce735874e58f7ea0`：Java 三元操作数误判修复及确定性、真实 Docker 回归。
建议合并这一通用修复和本轮证据；当前仅保存在任务分支，没有执行合并或推送。
产品演化未完成；工程修复可独立评审，不以工程测试代替产品验收。
