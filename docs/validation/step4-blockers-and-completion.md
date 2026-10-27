# Step 4 阻塞修正与最终验收

日期：2026-10-27。分支：`codex/resolve-step4-blockers`，起点 `10fa4e0`。
本轮授权：修正已复现的 DelveForge 缺陷、验证后按需重试，完成原四步；不得代写 Taskarium、重规划或弱化真实秘密保护。
当前记录进行中的工程周期，最终结果将在实际 Run、Delivery 和浏览器验收后填写。

## 历史事实与已确认缺陷

此前 Run `dc3465c3-4da8-4101-8dee-91f5482fc065` 在两个 `edit_file` 写入前以
`PATCH_CREDENTIALS/PATCH_CONTENT` 拒绝，目标 application.properties，new_text 行号为 6/9 与 7/10。
实际拟写入值未被保存，容器、卷和临时目录已清理，因此不能唯一恢复那两次输入，也不能把它们都宣判为误报。
旧报告与拒绝事件保持；没有补造原提案。

本地确认的相关实现缺陷是：Agent 的配置文件始终套用保守赋值匹配，将 `${DB_PASSWORD}`、
`${DB_PASSWORD:}` 等只有引用名而无凭据值的 Spring 配置，以及 YAML 空标量，列为秘密行。
新增最小回归在旧实现上有 6 项失败，无模型调用。它们是独立的通用配置夹具，不是历史草稿重建或 Taskarium 手工修补。

## 修正边界

只在 Agent 的 `.properties` / `.yml` / `.yaml` 视图和改动行检查中识别完整、单个、无非空默认值的引用。
YAML 支持空标量及引用后的普通行尾注释；不解析 AST 或任意表达式，不给测试目录整体豁免。
非空默认值、混合字面量、实际令牌、私钥及已有秘密行继续保护；Java 字符串 `${...}` 不被当成配置引用。
M1–M3 净化、明确凭据路径、写入前后像、停止后快照、Candidate 重放、授权与正式 Delivery 合同不变。
具体规则已同步 ADR-0017 与 AUTONOMOUS_AGENT.md。

## 确定性与部署前验证

- 配置识别及写入 Guard 定向回归通过；真实密码的替换、删除、改成引用及新增仍被拒绝。
- 真实 Docker 3/3：通过正式工具添加配置引用、读回、Maven 测试与 Candidate 冻结；实际凭据写入被拒且原引用不变；
  绕过工具修改秘密不能冻结；原通用夹具 Git 不变。
- 前端 19 文件、268 项通过，类型检查与生产构建通过；界面保持上轮简化结果。
- 完整后端 `mvnw -o verify`：BUILD SUCCESS，1681 执行通过、37 跳过，共 1718 项，耗时 11:23。

[已确认缺陷](evidence/step4-config-reference-defect.json)、[全量后端计数](evidence/step4-config-reference-backend-results.json)
与 [真实 Docker JUnit](evidence/step4-config-reference-docker.junit.xml) 分别保留证据。

本轮开始时已保存一致 SQLite 备份，原六个 Run、三个 Candidate/Delivery、四个 Step、Plan 定义与 C3 WorkingCopy 均已核对。
原注册仓库 73 个文件保持实验前大小及 SHA-256；本轮所有 Taskarium 修改仍必须来自实际 Agent。

## 实际重试与最终验收

### 周期 1：配置引用修正后的真实 Run

部署 `851218c` 后，通过实际页面的 JDK 17 与费用确认启动
`3d017a2c-4535-4d1f-aa52-f6f123c93b84`。
实际 52 次模型、90 次工具、1,316,017 tokens；以 `POLICY_REJECTED` 失败，未产生 Candidate/Delivery。
中途一次 Maven 28/28 通过，后续又有修改及失败检查，因此不能把那次通过当作最终源码通过。
最后正式检查报告 `CHECK_MUTATED_SOURCE`，唯一意外变化是已有 H2 trace 日志的内容。
前像 SHA-256 与 C3 中该文件完全相同；后像只保留哈希，正文已随临时副本清理。
[本周期安全摘要](evidence/step4-cycle-1-summary.json) 保留检查和拒绝的实际序列。

### 第二个已确认缺陷：隔离检查遗漏 H2 trace 追加

未修改任何 C3 源文件，在其一次性精确副本上用真实 H2 2.2.224 执行错误查询，
可复现原日志 73,368 字节到 73,582 字节的追加，以及旧策略拒绝；原前缀逐字节不变。
这证明正常 H2 行为存在兼容缺口；不能据此逐字重建历史 Run 的已清理后像。

只对检查副本中已有、与已识别 MVStore 数据库同名且有 H2 头的 trace，
在原长度、原前缀 SHA-256 和模式保持、追加具有 H2 头时，记录 `H2_TRACE_APPEND`。
真实 H2 时间戳包含小数和时区，回归覆盖该格式。日志正文不返回、不进 Candidate。
新增、删除、截断、覆写、模式或源码变化仍拒绝；权威源码与正式 WorkingCopy 不接收副作用。

新增真实 Docker 回归最初失败；修正后 DockerDependencyPreparationTest 4/4 通过，包括实际 H2 错误日志、
正式工具、源码隔离、测试绑定和 Candidate 冻结。Python/实际镜像协议 48/48 通过，无跳过。
最终打包通过，JAR 内 sandbox.py 与已测源码逐字节相同。
本次打包使用 skipTests，测试证据来自上述独立执行；先前完整 Java 1718 项是 `851218c` 的保留验证，未声称本次重新全跑。
[真实 H2 修正前后](evidence/step4-h2-trace-reproduction.json)、
[Docker JUnit](evidence/step4-h2-trace-docker.junit.xml)。

### 周期 2 与最终验收

部署 `ccb2be5` 后复核七个 Run、三个 Delivery、原四步、原授权、可信 C3 与原仓库 73 文件均未改变。
通过原页面费用确认启动 `126b58c3-6c3c-4f70-825f-f402c37d7ced`。
29 次模型、65 次工具、678,721 tokens；一次 compile 成功但未执行测试。
新增 AccountSecurityIntegrationTests.java 时两次 `PATCH_CREDENTIALS/PATCH_CONTENT`，Run 失败且无 Candidate/Delivery。
拟写入值未保存，不能判定这两次具体是完整秘密字面量还是其他表达式。
[周期 2 安全摘要](evidence/step4-cycle-2-summary.json)。

### 第三个已确认缺陷：直接字面量规则截取了表达式前缀

独立回归确认 `"case-" + UUID.randomUUID()` 被误认成完整硬编码凭据；旧实现 41 项有 1 项失败。
按 ADR-0017 既有不分析拼接片段的合同，仅排除字面量后继续 `+` 的源码表达式。
不解析数据流，不给测试目录豁免，完整直接赋值、已知密钥形态和配置保守规则不变。
这是同一编辑边界上可重现的实现缺陷，不声称恢复了上轮被丢弃的输入。

凭据规则、旧保守净化、写入 Guard、Agent 编排定向共 128/128 通过；实际 Docker 3/3 通过。
正式工具添加运行时生成值、Maven 执行、冻结均通过；将其改成完整凭据字面量被拒，原生成表达式保留。
旧秘密旁的安全修改可冻结，绕过工具的秘密改动仍拒绝。最终打包通过，JAR 内策略类和 sandbox 脚本与已测产物一致。
[回归计数](evidence/step4-source-expression-regression.json)、[Docker JUnit](evidence/step4-source-expression-docker.junit.xml)。

### 周期 3 与最终验收

部署 `621b074`、再次核对八个 Run、三份 Delivery、原授权和原 C3 后，通过正常页面启动
`995f76d8-74f2-4c2d-8590-213c3ef7f108`。
93 次模型、129 次工具、2,700,605 tokens，最终 TOOL_ERROR，无 Candidate/Delivery。
原 28 项测试及新 9 项账户检查曾通过；跨重启测试持续出现执行错误，后续又有修改，没有最终全量通过证据。
[周期 3 安全摘要](evidence/step4-cycle-3-summary.json) 保留完整检查计数与终止位置。

### 第四个已确认缺陷：只读预演失败被当成致命错误

实际终止日志为 requireToolSuccess:492，由 requirePatchContent 对 inspect_patch 的只读结果调用；
真正 apply_patch 尚未调用。旧异常正文未持久化，不能唯一恢复具体错误。
独立真实 Docker 夹具用不闭合引号的 diff 头可复现相同终止路径：四种固定消息之外的只读解析错误直接使 Run 失败。

修正仅在只读 inspect_patch 返回 TOOL_ERROR 时交回未写入的预检查失败，固定诊断 INSPECTION_UNAVAILABLE，
不返回异常正文；沿用重复补丁/三次未解决冲突停止。后续仍需有效预演、凭据检查和完整前后像。
策略拒绝、无效协议、实际应用后结果不明继续终止，未将未知写入伪装成冲突。

定向 115/115、真实 Docker 4/4 通过，覆盖失败后源码不变、有效编辑、正向测试、冻结以及秘密保护。
打包通过，部署 JAR 内 AgentToolActivity、凭据策略与 DockerAgentSandbox 类均与已测类逐字节一致。
[回归计数](evidence/step4-inspection-regression.json)、[Docker JUnit](evidence/step4-inspection-docker.junit.xml)。

### 周期 4 与最终验收

部署 `9c5d606`，核对九个 Run、三份 Delivery、原授权、可信 C3 与原仓库后，实际启动
`39ae48f2-70cb-48c4-9326-7940233732e4`。55 次模型、86 次工具、1,449,742 tokens，
最终仍在补丁预演以 TOOL_ERROR 终止。原 28 项通过后继续修改，新增 1 项检查持续执行错误；没有最终正向测试或 Candidate/Delivery。
[周期 4 安全摘要](evidence/step4-cycle-4-summary.json)。

### 同一预演边界的实际协议错误

进一步定位：Python 对正常预演冲突返回 `ok=false`，内层 result 为 PATCH_CONFLICT 且明确未写入；
Java 原来反而要求 ok=true。该分支不属于上一修正的顶层 TOOL_ERROR，随后进入 requireToolSuccess，
因顶层分类缺失而退化为 TOOL_ERROR。这是确定的跨语言协议不匹配。
真实 Docker 使用有效但上下文过期的补丁，在 `9c5d606` 上复现同样的 requireToolSuccess / requirePatchContent 终止路径。
旧 Run 原始补丁和响应未保存，不能补造其精确内容；日志位置、运行产物和当前代码支持上述路径解释。

修正要求布尔 ok=false、内层 PATCH_CONFLICT、布尔 mutationNotStarted=true，直接返回预演冲突，
不进入实际写入；缺少明确标记仍失败关闭。
定向 115 项与真实 Docker 4 项共 119/119 通过，最终打包通过。
回归包括有效上下文冲突、源码保持、后续有效编辑、正向测试及冻结，并继续验证秘密保护。
[协议回归](evidence/step4-native-conflict-regression.json)、[Docker JUnit](evidence/step4-native-conflict-docker.junit.xml)。

### 周期 5 与最终验收

待部署已验证的原生冲突协议修正并核对可信 C3 后执行。
