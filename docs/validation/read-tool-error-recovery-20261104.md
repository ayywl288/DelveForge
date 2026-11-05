# 只读工具输入错误与 Agent 可靠性修正（2026-11-04）

本次只做工程修正，独立本地分支提交，不合并、不推送，不发起付费 Run。原真实 Run
`1cab61f7-e8b9-469b-8544-87add6004c1b` 仍是 FAILED，无 Candidate/Delivery；本次合成回归不追认其成功。

## 根因与完整传播路径

模型调用先经 `AgentTools.validate`，再由 `CodingAgentService` 调用 `DockerAgentSandbox.tool`。
Java `requirePath` 的 PATH_FORMAT 异常被按 POLICY_REJECTED 传播；编排只反馈既有 INVALID_ARGUMENTS
及特定写入前凭据修正，其他异常终止 Run。旧 Run 最后一次 read_file 就在 Java 路径校验终止，
没有完整工具返回。原路径未保留，不能认定它合法，也不能认定校验误判。

容器内另有两点：缺失文件、范围及分页错误原来只是笼统 TOOL_ERROR；read_file 甚至先读内容再验范围。
这些已有返回通常不直接终止 Run，但反馈不充分，且不能声称没有读取。现在先验范围，再读内容；
明确缺失或参数错误返回固定、无输入回显的 `INVALID_TOOL_ARGUMENT`。编排按原 call ID 记录并反馈，
压缩后仍保留 reason、纠正说明和 `readNotStarted` / `mutationNotStarted`。

| 类型 | 现在的结果 |
| --- | --- |
| 普通相对路径中的反斜杠、`./`、重复/末尾分隔符 | 拒绝操作，固定 RELATIVE_PATH_REQUIRED，原活跃 Run 可纠正；不会规范化后读取 |
| 不存在的普通源码路径 | PATH_NOT_FOUND，提示 list_files 查找准确路径 |
| 非法行范围、分页、过长/空搜索词、schema 类型/字段错误 | 对应固定原因与参数说明；不读取源码内容、不写入 |
| 绝对/驱动器/UNC 路径、`..`、凭据/元数据/受保护资源、链接 | 既有安全拒绝保持；混合错误格式不因此放行 |
| 所有权、隔离、网络、前后像、未知写入结果、冻结/Delivery 证据错误 | 仍按原有 fail-closed 路径处理 |

路径恢复仅允许逐段通过既有路径检查的普通相对拼写；不捕获所有 policy 异常。写工具不进入此分类。
Python 的 PolicyError 不转为普通读取错误。既有源净化、生成物说明、Step 授权和原资产隔离保持。
重复相同错误复用 AgentProgress：连续三个相同无进展动作触发 NO_PROGRESS；成功编辑仍清除对应无进展记录。
没有累计调用/Token/编辑次数上限，也没有新重试框架。

## 变更与验证

- `AgentTools` 定义固定只读错误原因和结果；`CodingAgentService` 反馈 schema 错误并保留压缩摘要。
- `DockerAgentSandbox` 只在读取入口区分普通拼写；`sandbox.py` 在读取前验参数并明确缺失路径。
- `AgentToolActivity` 保留白名单原因及无读写标志，不保存参数正文或不安全路径。
- 回归覆盖纠正读取、查找缺失路径、范围/分页/查询、调用身份、成功编辑保留、压缩、NO_PROGRESS，
  并复测安全、补丁批次、未知写入和 Candidate/Delivery。未改领域状态、持久化、迁移、API 或依赖。

| 本轮检查 | 最终结果 |
| --- | --- |
| Java 定向编排、活动元数据、路径策略、补丁修正 | 145 通过，0 失败/错误/跳过 |
| Docker 专项：真实只读纠错、原补丁批次、凭据/链接/生成物、协议 | 24 项中 23 通过、1 跳过，0 失败/错误；其中 7 项实际使用 Docker，跳过的是未配置旧镜像对照 |
| Linux/Python/Git 补丁与安全回归 | 46 通过，0 跳过 |
| 完整 Maven Wrapper verify（本机权限重跑） | BUILD SUCCESS；1919 项中 1861 通过、58 跳过，0 失败/错误；四个后端模块均构建成功 |

完整验证分项为 domain 209/0 跳过、application 882/0、infrastructure 602/55、app 226/3。
包含 41 项真实 Git/SQLite CandidateDelivery、5 项 DeliveryProcessRecovery 和 2 项债务交付 API 回归。
普通权限下首轮 verify 在受控本地 Git clone 及相关环境断言处失败（基础设施 1 failure/63 error）；
没有改实现绕过，恢复所需本机权限后原命令完整通过。两次日志均保留。

真实纠错测试 Run `63c4012d-ea8b-4e91-a0ca-a24975eb4af4`：5 次脚本模型响应、10 次工具调用，
5 个普通错误得到固定反馈，后续准确读取保留先前成功修改；完整 Maven 的 2 项测试通过，冻结
`FULLY_TESTED` v5 Candidate，原资产和正式副本保持基线。无 Provider 请求，该测试未执行 Delivery。
详情由 `ReadToolRecoveryDockerTest` 断言，完整工具事件保存在
`backend/delveforge-infrastructure/target/read-tool-recovery/63c4012d-ea8b-4e91-a0ca-a24975eb4af4.json`。
本轮镜像为 JDK21，ID `sha256:0028af7e325a8ff25bdcb6d0bb66f9f1b85eb0589f692010e2b3a4889918cb22`；
镜像与仓库 sandbox.py 的 SHA256 都为 `6720304167eacee88fd40f894771e0098460eb5b2503832155a9d5ae22cc8725`。
本次未另跑 JDK17、完整前端测试或真实付费演化；不能称所有 Docker/live 组合都通过。

3 项新 Python 方法对原版脚本复现失败，对修正后脚本通过。Java 首轮两个新夹具失败（缺少来源路径、
误要求仅 Java 就拦截原本由容器拦截的 NUL）；修正夹具后重跑通过，没有放宽实际安全断言。
执行日志在 `target/read-tool-recovery-{focused-final,docker,python,before,verify,verify-elevated}.log`。

## 旧失败的可靠性观察

以下来自旧 Run 的 742 项保留事件及用量，不来自模型的私下思考：

- 160 次模型响应、207 次工具尝试；73 次 read_file、8 次 list_files、9 次 search_code。
  同一后端测试文件读取 10 次、对应服务读取 9 次；这证明重复探索，但元数据没有行范围/源码全文，
  不能认定每次读取都多余。后续可评估基于实际差异的更窄读取，先保留上下文正确性。
- 提示 Token 合计 10,310,794，占总 10,357,386 的约 99.6%；首轮 3,864，中位数 53,119.5，
  峰值 135,004。第 109 次从 135,004 降至 19,686，与现有压缩相符；不能仅由此归因于某个工具正文。
  后续优先量化各类工具输出占用，避免直接引入新上下文系统。
- 95 次写入工具尝试，81 次 PATCH_APPLIED、14 次 PATCH_CONFLICT，另有一次 PATCH_NOT_EXECUTED。
  同一后端测试文件有 18 次写入尝试；后端检查曾从编译失败走到 43/35 项通过，说明检查结果有改善，
  不能据此证明功能进展或测试质量未降低。
  前端最后却是 392 项中 7 failure，不能把重复修改概括成全无进展，也不能宣称目标已完成。
- 后端 14 次检查，实际测试数量常为 30/35/43，远少于基线完整 270 项。现有事件未记录选择器，
  不能精确反推每次参数；只支持“缺少修改后完整后端检查的证据”。前端有 7 次实际测试且均失败，
  另一次 build 通过。后续应在完成前运行同一最终快照的完整检查，并保留选择器用于诊断。
- 无 MODEL_COMPLETED/FINALIZING 事件；本 Run 没有证据表明提前提交完成。更早 100 次调用的 Run
  在完整后端 280 项仍有 3 failure/31 error 时声明完成，冻结以 UNRESOLVED_CHECK_FAILURE 拒绝；
  此事实来自[当时记录](source-protection-execution-20261104.md)，不能混作当前 Run 的行为。
  既有完成反馈和冻结检查继续独立阻止未验证结果。

原 source 与正式 WorkingCopy 的 360 个跟踪文件、HEAD、index 和状态重新核对一致；旧 Run 用量、
失败原因及保留标记不变，Plan ACTIVE、Step/WorkingCopy READY、Candidate/Delivery 都为 0。
没有修改第三方源码、旧失败记录或清理策略。未新增 PAUSED/RESUMING、checkpoint、跨进程恢复或对话重建。

本次工程回归只能证明同一活跃 Run 的纠错链路。旧拒绝路径未知，不能保证它会进入本次窄恢复分类；
若属于安全目标，仍应终止。原随机全栈产品仍未验收成功。最小下一步是用户另行授权后，重建所选
JDK 镜像、核对实际脚本与应用包，以原固定仓库/Plan/Step 启动正常新 Run；要求最终快照完整检查，再通过正常
Candidate/显式债务确认/Delivery 和三个已确认业务反例验收。本次不启动该付费试验。
