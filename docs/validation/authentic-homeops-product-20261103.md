# 真实完整产品演化试验：家庭运维到期与待办板

**ASTRA_READY = NO。** 本轮真实流程完成画像、仓库分析、四个方向及四步计划，第一步的真实 Agent 在最终化时因 `UNRESOLVED_CHECK_FAILURE` 失败。没有 Candidate、正式 Delivery 或完成的产品。工程门槛与产品结论分开，见 [工程报告](final-maven-diagnostics-20261102.md)；完整生成内容与数值见 [结构化记录](authentic-homeops-product-20261103.json)。

## 基线和非干预

- DelveForge：`codex/full-stack-evolution-checks`，工程提交 `bc44e689520a4d94c346410c5c581c8683b139e5`；沿用 `2396d1a` 接入设计。
- 原库：`bastienwirtz/homer`，Apache-2.0，固定 `daa017dfe1ea8d0875697aede091319b6134bb4b`。
- 试验：`target/final-maven-product-20261102/product-trial-01/`，独立 SQLite、工作副本及 Agent 根目录；实际 DeepSeek 配置 `deepseek-flash`。
- 使用最终 v6 JDK21 镜像及固定 Node 22.16.0 / npm 10.9.2 / pnpm 12.9.1。公共依赖缓存沿用，不能称冷缓存验证；没有复用历史模型会话或目标源码改动。
- 用户本轮直接回复“不限制”，覆盖附件数字上限要求；没有因真实 Agent 失败追加付费重试、重规划或换库。
- 目标源码、测试、脚本及配置改动全部由 DelveForge 自身 Agent 提出并应用。外部仅发起正常 API、观察和只读诊断，没有代修目标、改数据库、伪造响应或覆盖失败。

结束时原库 232 文件 SHA-256 全部一致，Git 干净、revision 未变。正式 WorkingCopy 同样干净，HEAD、currentRevision、lastVerifiedRevision 均保持原固定 revision。保留的尝试索引中有 13 个文件的改动（1107 增、3 删），**它不是已冻结 Candidate 或已交付代码**。

## 真实发现与选择

预先冻结的虚构用户林乔是自由职业剪辑师，业余维护家庭 NAS 与自托管服务。伴侣不懂技术，父母偶尔看家庭相册。他已有 Homer 入口页，维护事项散在便签和聊天中、忙时遗忘，家人遇到问题依赖电话求助；会基础 Vue 和 Docker，每周约六小时，不愿维护复杂后端。以上是合成人设，不是真实用户调研。

正常 Discovery 经真实模型提取与充分性评估后，Profile `51d6bde5-465e-4343-b1d3-6e78f1a5d9fa` revision 18 进入 REVIEWING，再通过确认 API 到 CONFIRMED。没有直接填写画像各字段。真实仓库分析快照 `0851dac0-9f33-4789-b229-b6c2bd15bded` 记录 Homer 的配置、卡片、调度、主题、静态 PWA 能力及浏览器直连、无既有测试等限制。

模型实际生成四个方向，原内容全部保留在结构化记录：

| 方向 | 主要转变 | 选择 |
| --- | --- | --- |
| 家庭服务状态与下一步提示板 | 从入口转为状态和家人行动说明 | 保留候选 |
| 家庭运维到期与待办板 | 本地录入、到期计算、完成/备注/稍后提醒 | SELECTED，`c9091d9a-55b3-47fc-a6aa-9ddc72fbc51d` |
| 家庭故障复盘与家人操作手册 | 结构化故障记录和安全步骤 | 保留候选 |
| 本地健康检查器与家人状态页 | 新采集容器脱敏发布服务状态 | 保留候选 |

按发现前声明的需求适配、复用和可行性原则，选择第二项：直接解决维护事项遗忘，具有可验证的完整操作链，适合现有前端和时间投入；无需新增采集后端。没有拼接或重写模型方向。方向的个性化关联可从画像和仓库依据追溯，但产品价值仍未经实际家庭使用验证。

## 真实计划与所有步骤

Plan `ca223f53-2a31-4772-8293-ca8e500a1750` 的四步由实际规划响应产生，外部没有指定步骤数量或内容。正常 prepare 创建 WorkingCopy `1ee3c3c5-125a-4937-906e-f2c2d60db83a`。

| 顺序 / Step | 真实目标 | 最终事实 |
| --- | --- | --- |
| 1 / `68eab373-d17a-424e-8f5e-802a68f79a50` | 只读到期看板、日期状态、示例和入口、自动化测试 | 正常确认；真实 Agent 最终化失败；领域 Step 仍 READY，未成功 |
| 2 / `7cabff15-95ed-4be5-87c7-0c7537a7a1a5` | 创建编辑、校验、本地保存恢复 | PENDING_CONFIRMATION，未运行 |
| 3 / `bb25d460-a9e7-4805-a5b5-899bd2ff1f4d` | 完成、备注、稍后提醒和状态重算 | PENDING_CONFIRMATION，未运行 |
| 4 / `decc5081-6aea-467f-9154-b75264ff0aa2` | 家人汇总、JSON/YAML 导入导出和导航整合 | PENDING_CONFIRMATION，未运行 |

Plan 保持 ACTIVE，WorkingCopy 保持 READY，完成步骤 0/4。没有绕过第一步失败继续执行，也没有调用收尾命令制造 COMPLETED。

## 两个 Run 分开保留

第一 Run `5bdb4085-231d-4335-b2b9-7c0e2f9a7af3` 在 PREPARING 因 Docker daemon 未运行失败，模型调用 0、工具调用 0、Candidate 为空，记录 CLEANUP_BLOCKED。

本机 Docker Desktop 启动又被不可访问的临时套接字阻断。普通单文件改名/移除均被 Windows 拒绝；停止 Desktop 后，只将已核对仅含零字节套接字的临时目录改名保留，再正常启动，Docker 29.4.0 恢复。没有恢复出厂、删除镜像/卷、改产品代码。目录与过程见 `evidence/docker-runtime-recovery.json`。专用后端重启后，由现有协调器清理旧 Run 并释放占用。恢复过早的一次启动请求返回 RUN_CONFLICT，没有创建新 Run 或调用模型；协调完成后正常入口接受新 Run。不能把环境失败抹去并称为一次无中断成功。

第二 Run `00004ef2-4a61-4882-b214-31376724f1ef` 实际编码约 22 分 47 秒，88 次模型调用、96 次工具调用。发生源码修改、检查、修正和重验，最终模型结束后，系统独立冻结时拒绝：

| 实际检查 | 次数 | 含义 |
| --- | --- | --- |
| pnpm 构建失败 | 3 | FRONTEND_BUILD_FAILED，保留历史 |
| pnpm 构建通过 | 6 | BUILD_PASSED_NOT_TESTED，执行测试数均为 0 |
| 测试配置不支持 | 2 | UNSUPPORTED_FRONTEND_CONFIGURATION，未执行测试 |
| 类型检查不可用 | 1 | CHECK_NOT_AVAILABLE，不算通过或测试执行 |

只读检查保留的尝试索引确认：Agent 新增 `test: node --test tests/` 及 `tests/opsBoard.test.mjs`，没有使用当前受控接口支持的 `vitest` / `vitest run`。添加测试源码不等于执行过测试。已记录的 FRONTEND/./test 失败没有匹配的当前成功重验；构建通过不能清除它，`AgentCheckEvidence` 因而抛出 UNRESOLVED_CHECK_FAILURE。没有证据支持放宽接口或冻结守卫；本轮不扩展 Node 原生测试支持来挽救演示。

Candidate 表和 Delivery 表计数均为 0，没有 Candidate/Delivery revision。Run=FAILED、retained=true、occupied=0；专用 Agent 和代理容器均已停止。按既有失败最终化合同，源卷、依赖准备卷、m2、网络和尝试 Git 材料有意保留；**不是完整清理成功**。未外部删除这些证据或强制改变状态。

## 独立验收与用量

没有完成的 Plan 或正式交付应用，独立功能、导航、状态变化、持久化、边界和视觉验收均为 **NOT_RUN**；没有 before/after 截图。未启动未接受的尝试代码冒充成品，未声称 DelveForge 执行浏览器 E2E。个性化发现已真实执行，完整产品转变未实现，不能作为成功宣传案例。

- Discovery、仓库分析、方向和规划：6 次实际 Gateway 调用，输入 44,785、输出 29,297 tokens。
- 编码 Agent：88 次调用，输入 3,659,435、输出 25,999，合计 3,685,434 tokens；其中报告 cache-hit 3,559,680，cache-miss 未报告。
- 本轮合计 94 次调用，输入 3,704,220、输出 55,296，合计 3,759,516 tokens。前阶段报告 reasoning 21,488，包含在输出统计内，不重复相加；Agent 没有相应细分记录。
- 没有独立供应商账单，实际金额 **UNKNOWN**；没有用账户余额差推算本轮费用。

专用服务已经停止，SQLite 和一致备份保留，不再有本轮运行中的模型任务。此前 IT-tools 小步骤失败、公开样本失败和本次两个 Run 全部分开保存。原始 API 响应、事件、用量、源完整性、只读持久状态和资源记录均在试验目录；结构化提交保留实际方向与计划，不包含 API key。未推送或合并，未修改 `frontend/design/` 或 `grilling/`。
