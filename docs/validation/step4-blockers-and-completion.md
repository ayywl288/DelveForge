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

尚未因本轮修正启动新 Run。修复完整验证、部署并重新核对授权和可信 C3 后，才通过既有费用确认开始。
每次后续工程周期都必须有新的实际证据或已验证修正，不能盲目重复付费。
