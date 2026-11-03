# Homer 原计划真实重试：家庭运维到期与待办板

日期：2026-11-03。**AUTONOMOUS_DELIVERY_READY=NO；PRODUCT_READY=NO。完成 0/4 Step，无 Candidate、无 Delivery、无接受的演化版本。**
本次不是工程夹具：使用真实 DeepSeek、原持久画像/方向/计划及正常产品 API。工程通过与产品失败分开报告，工程记录见 [沙箱脚本验证](sandbox-script-evolution-20261103.md)。

## 固定基线与授权

运行工程提交 `3914cf977db743e62dccef4822d5890833b6237a`，JAR SHA-256 `8A6EBA7F57FF4677DD4F43E5D75551E8CEE2F3F89E08AA35551E060A06C7348B`。
用户在本轮明确确认预算充足并授权该计划的一次四步试验；API key 仅进入独立 Java 服务环境，未写入源代码、提交或本报告。没有复用旧实验的无限预算授权。
复用 [先前真实发现与规划](authentic-homeops-product-20261103.md) 的有效事实，没有重做收费 Discovery、重写模型方向、重新规划或直接改写领域数据库：

- Profile `51d6bde5-465e-4343-b1d3-6e78f1a5d9fa`，已确认 revision 18；原人设为明确标注的虚构家庭用户。
- RepositoryProfile `0851dac0-9f33-4789-b229-b6c2bd15bded`；Homer 固定 `daa017dfe1ea8d0875697aede091319b6134bb4b`。
- Selected Direction `c9091d9a-55b3-47fc-a6aa-9ddc72fbc51d`；Plan `ca223f53-2a31-4772-8293-ca8e500a1750`。
- WorkingCopy `1ee3c3c5-125a-4937-906e-f2c2d60db83a`，初始 READY，两个可信 revision 均为原固定版本；第一 Step 已 READY，后续尚未确认。

独立验证服务绑定 127.0.0.1:8198，实际监听 PID 与记录启动进程一致；没有替换 Coding Agent、模型输出、目标源码或测试。使用既有公共工件缓存，不称冷缓存试验。

## 正常执行与实际终态

通过正常启动 API 创建新 Run `f8551b83-af4a-4df2-9f03-84d13e9d8f34`，请求携带已获授权的 paidUsageAcknowledged=true。原失败 Run 没有恢复或改标成功。

| Step | 最终事实 |
| --- | --- |
| 1：只读到期概览及入口 | 真实 Agent FAILED / GENERATED_OUTPUT_AMBIGUITY；Step 仍 READY；无 Candidate |
| 2：创建编辑、校验、本地持久化 | PENDING_CONFIRMATION，未执行 |
| 3：完成、备注、稍后提醒、状态重算 | PENDING_CONFIRMATION，未执行 |
| 4：家人汇总、导入导出、导航整合 | PENDING_CONFIRMATION，未执行 |

Run 从 `2026-11-03T04:51:07.235834800Z` 到 `2026-11-03T04:56:43.503150100Z`。Plan 仍 ACTIVE，WorkingCopy 仍 READY；没有跨过第一步失败去授权后续 Step。

Agent 有 16 次成功编辑工具事件，涉及 5 个可见路径；这些是未接受的沙箱修改，不是冻结差异或交付产品。唯一一次前端检查尝试返回 LOCKFILE_INVALID，executedTests=0，没有真正运行前端测试或构建。不能把工程 Node/Vitest 夹具通过算成本轮真实产品检查通过。

## 精确失败点与后续工程修正

在排查锁文件之后，Agent 调用 `list_files({"path":"node_modules/.bin"})`。DockerAgentSandbox.requirePath 在进入文件系统前以 GENERATED_OUTPUT_AMBIGUITY 拒绝；异常直接终止了整个 Run。依赖实际只存在于一次性检查副本，不在 Agent 权威源码视图中。
这一次不是旧 Vitest 语法白名单拒绝，也没有真实测试失败/通过或冻结成功。锁文件错误的完整进程输出未进入持久化活动摘要，不能据这些摘要断言它已修复。
本次付费试验至此结束。随后在本任务内补充窄范围工程修正：生成材料的 list/read/search 请求返回 CHECK_MATERIAL_UNAVAILABLE 和实际检查入口说明，不读取这些路径；生成物写入仍拒绝。初始能力及提示说明依赖只在检查副本中。新增实际 Docker 回归验证拒绝后继续失败修正、重验、冻结和 Delivery。
**该后续修正没有进行新的真实模型重试，不回写本 Run 的结果，也不提升本页 B/C 结论。** 最终工程验证及版本边界见工程报告。

## 用量、完整性和资源

| 本轮新增用量 | 实际值 |
| --- | ---: |
| 模型调用 / 已记录响应 | 32 |
| 工具调用 | 47 |
| 输入 tokens | 703,986 |
| 输出 tokens | 11,974 |
| 合计 tokens | 715,960 |
| cache-hit tokens（供应商报告） | 662,784 |
cache-miss 未报告，实际账单金额 UNKNOWN；未使用余额差或字符数估算费用。没有新 Discovery/规划调用或失败后的付费重试。

原资产与正式副本各比较 232 个文件，0 改动，Git 干净，HEAD 均保持 `daa017dfe1ea8d0875697aede091319b6134bb4b`；正式副本无 node_modules。
与启动前一致备份逐项比较：原两条 agent_run 完全一致，所属 381 条 agent_event 完全一致；旧失败原因、保留标记、时间与用量未改写。新 Run occupied=0、retained=false，按既有中途失败策略清理了私有源码/依赖现场；没有不可变 Candidate 源包可交付。历史已保留的旧失败现场没有被外部删除。
验证 Java 服务已停止；本轮没有继续调用模型或启动其他仓库实验。原始 API、事件、用量、历史/源完整性和进程记录位于 `target/script-evidence-20261103/product-trial/`。结构化记录见 [真实试验证据](autonomous-homeops-retrial-20261103.json)。

## 产品验收与限制

没有接受的演化版本，独立构建、启动、浏览器用户旅程及 before/after 截图均 NOT_RUN。没有启动未接受的尝试代码冒充产品，也没有手工修补 Homer。
当前不能宣称家庭任务的创建编辑、完成/稍后提醒、刷新持久化、汇总或导入导出已交付。最终后续工程修正只取得确定性证据，仍缺新的真实 Coding Agent Candidate/Delivery 和完整四步产品验收。未推送或合并。
