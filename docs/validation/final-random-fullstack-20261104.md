# 本地合并、安全清理与随机全栈产品试验

日期：2026-11-03 开始，2026-11-04 续跑（Asia/Shanghai）。只操作本地 main，没有推送。

## 结论

**MERGED / 文档与本地输出 CLEANED；真实随机试验 BLOCKED，不能作为成功产品演示。**
首个产品阻塞是 Repository Analysis 缺少 DeepSeek API Key；不是上游构建失败，也不是 Agent 实现或业务验收失败。
Docker Desktop 同时出现独立启动故障，Docker 清理未执行；资源保留，未把无法取得的后置清单写成成功。
建议：补齐外部凭据并恢复 Docker 后，从同一已选资产继续；当前没有证据要求修复 DelveForge 代码。

## 合并与本次实际检查

- 原 main `7f74810`；已核对 feature `eb6ae12` 的祖先、唯一提交和变更范围。
- 非快进合并：`f4b69bbee7905bf663d43f7571590065887705fe`。
- 独立文档整理提交：`02b3bbf9bc87dd91b4417ea5a4366fc4b6889b50`。
- `git branch -d codex/native-build-preflight` 成功；仅剩 main，一个工作树 `E:/develop/DelveForge`。
- 原有 `frontend/design/`、`grilling/` 未动；清理前后对其中 8 个文件核对 SHA-256，相同。

历史 [原生预检记录](native-build-preflight-20261103.md) 与证据已核对，不称为本轮重跑。
其中后端 **1859 项总数、52 跳过**，实际非跳过 1807 项，0 failure/error；不能写成“1859 通过另加 52 跳过”。
历史五项真实 Docker Candidate/Delivery 使用确定性模型，不是本轮付费产品结果。

本轮合并后实际运行（2026-11-03 22:05–22:06）：

| 命令 | 本次结果 |
| --- | --- |
| `.\mvnw.cmd -pl backend/delveforge-domain,backend/delveforge-application -am test -DargLine=-Xmx512m`，MAVEN_OPTS=-Xmx384m | domain 209 + application 863，共 1072，0 failure/error/skip |
| `.\mvnw.cmd package -DskipTests` | 全模块打包成功；此条没有执行测试 |
| `pnpm --dir frontend test` | 22 文件、308 项通过 |
| `pnpm --dir frontend typecheck`、`pnpm --dir frontend build` | 均通过 |

没有重新运行完整 `verify`、五项 Docker 交付或声称随机目标的 Maven/前端检查通过。

## 安全清理与文档

已移除可重建的 frontend/dist（8 文件），三个旧 native fixture/采集脚本，以及本轮错误查询 scratch 和
已转为净化清单的原始 Docker inspect 文件；合计 13 文件、1,087,717 字节。
保留 Maven 测试日志、提交的验证记录、当前打包 JAR、主数据库及 WAL/SHM、workspaces、agent-runs、历史归档。

清理前 Docker 清单：17 个停止/未启动容器、9 个网络、62 个卷、98 条镜像记录。
失败测试日志证明 `fa2bbcc0-4abb-4d33-80a8-f45e2cdd50fd`（NativeFrontendDeliverySmokeTest FAILED）
和 `ad0f33a6-ef8b-49e6-830b-8f5b89b802a2`（隔离 fixture 断言失败）可进入清理复核。
但续跑时引擎已停止；启动报 `initializing Inference manager ... dockerInference: file cannot be accessed`，
最后一次 `docker info` 20 秒超时。清理脚本停在首次重新枚举，**没有到达任何 rm 命令，Docker 删除数为 0**。
本任务悬挂命令已停止；未重置 Docker、未清理磁盘镜像，后置资源数量为 NOT VERIFIED。

以下全部保留：两个当前 v8 final2 JDK17/21 镜像、共享 Maven/npm 工件缓存、其他真实/恢复 Run 的卷与容器、
showcase/step4 历史材料、所有身份不足的孤立卷、mysql57/libretv/redis 等无关项目。
前置逐项身份、后置不可用状态和实际删除清单见 [清理与执行证据](final-merge-cleanup-evidence-20261104.json)。

两个独立操作文档已删除，现行 v8/v4 使用说明归入 [LOCAL_SETUP](../LOCAL_SETUP.md#agent-build-preflight)。
保留 ADR-0021/0022，初次 v4 验证迁入 [历史记录](frontend-checks-initial-20261102.md)；
PRODUCT、ARCHITECTURE、ROADMAP、ADR 和旧验证的引用均更新。旧版本语句标明历史语境。
归并后 49 个已跟踪 Markdown 文件检查了 113 个本地链接、16 个本地锚点，未发现失效；本报告新增 5 个本地链接及 ROADMAP 的报告入口也已核对。

## 一次随机选择

GitHub 两个预声明搜索合并去重 148 个仓库；按公开、非归档、许可证、同仓 Java/Maven + Vue3/Vite、
真实业务路径、固定 commit 与源码预算（10000 文件、128 MiB 总量、16 MiB 单文件）核实，冻结 32 个候选。
不根据锁文件、管理器、Maven profile/根数、镜像源、测试或预计成功率筛选，也未预运行候选的构建或兼容检查。
旧 26 仓兼容语料和既有演示被预声明排除；该旧名单与最终候选无交集。
完整名单、每个 commit/许可证/源码计数、资格证据路径、148 项排除审计和全量排名见
[随机抽样清单](final-random-fullstack-sampling-20261104.json)。随机性限于这个搜索发现池，不代表整个 GitHub 的均匀抽样。

冻结文件 SHA-256：`dd951cfdac35310a7c3a4cf21057c0fe38087ebc12ba962b105ff85dbd32237b`。
冻结之后仅生成一次 32 字节随机种子：

`d38ed9c95941942ed2cd9a5a60e740dd4dd60fda238cc9f2fbe9ffb55aec27f7`

对按名称排序的候选计算 SHA256(UTF8(seedHex + LF + full_name + LF + commit))，选择十六进制摘要最小者。
从保存的清单重算排名一致；重抽 **0 次**。一次终端 Unicode 输出错误发生在 selection.json 保存之后，没有再生成种子。

选中 [校园实验室设备预约系统](https://github.com/Techd81/Campus-laboratory-equipment-reservation-system-based-on-Spring-Boot-Vue/tree/307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05)，MIT。
固定提交 `307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05`，tree `8f05690251b35365a5a5d60a5ddcd5d99bc38ec2`。
360 文件、1,582,431 字节；backend/pom.xml、Vue ^3.5.0、Vite ^6.1.0。
抽样后核对 LICENSE 和预约前端 API/后端 Controller，确有设备预约、取消及查询业务连接；没有运行上游代码。

## 正常产品流程的实际边界

以合并后正式 JAR 启动，端口 8300，独立权威数据根 `data/random-fullstack-20261104/`，没有使用测试替身或直接写数据库。
试验前声明虚构学生画像意图：课前可靠预约设备、理解时段/规则及预约变化反馈；不称真实用户研究，不指定易通过的功能。
只有分析成功后才由产品正常生成画像、方向与计划。操作边界为同一仓库、一次生命周期尝试，最多一个有意义 Step/编码 Run；首个阻塞即停，不自动付费重试。

| 阶段 | 实际结果 |
| --- | --- |
| 正式应用启动、GET /api/system/connectivity | 200 UP，仅证明 HTTP 可应答 |
| POST /api/software-assets | 201；显式读许可、MIT 与 ALLOWED；asset `8615a13e-31aa-44bc-91bf-ece127dccda1` |
| GET 该资产 compatibility（指定 commit） | 200 COMPATIBLE，识别 backend/JDK17；仅静态 Git/资源能力，不是预检就绪 |
| POST 该资产 analysis | 502 EXTERNAL_CAPABILITY_UNAVAILABLE，0.5 秒；当前进程/User/Machine 没有 Key，AiGateway 在 HTTP 请求前 requireApiKey 拒绝 |
| 画像、方向、选择、Plan、官方 WorkingCopy、Step 确认 | NOT RUN，分析前置条件未完成 |
| 付费确认、Environment Preflight、真实 Coding Agent | NOT RUN；不能把静态 COMPATIBLE 当 READY |
| Candidate、Delivery、应用业务验收 | NOT RUN，没有相关 ID 或交付 revision |

产品首个失败分类：**外部运行配置缺失**；Docker 启动故障是另一独立外部前提。
没有真实 Provider 请求；没有 Agent Run，因此 modelCalls/tokens 没有运行度量或 Provider 用量回执，不能伪造完成的零 token 编码 Run。
只读查验试验 DB：asset=1；RepositoryProfile、UserProfile、方向、Plan、WorkingCopy、Run、Candidate、Delivery 均为 0。
资产静态准入仍保留部分源码/测试为主机材料；其对真实检查的影响尚未执行验证。

## 保留与最终状态

原始随机仓库 HEAD/tree 与登记时相同，`git status --porcelain` 为空；没有补丁、构建输出或依赖安装进入原库。
选中源码 `data/random-fullstack-20261104/source/`、试验数据库及其 SQLite 文件、product-api-evidence.json、启动日志全部保留。
正式 WorkingCopy/Candidate 未创建；没有可删除的本轮运行容器/网络/卷。试验应用及本任务挂起命令已停止。
原有 `data/delveforge.db`、workspaces 与 agent-runs 未用于本轮写入；主工作树仅保留两项原有未跟踪目录。

**PASSED**：合并、列出的本轮冒烟、文档引用、资产登记、静态准入与源完整性。
**BLOCKED**：Repository Analysis 凭据、Docker 资源后置复核/清理。
**NOT VERIFIED**：随机目标的原生环境预检、Maven/前端执行、真实 Agent、Candidate/Delivery、业务与视觉验收。
没有据此宣称广泛兼容或完整产品可用，没有追加工程修复来挽救演示。
