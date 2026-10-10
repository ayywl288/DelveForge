# M4 Delivery Closeout

日期：2026-10-10（Asia/Shanghai）。本记录只覆盖维护分支整合、本地配置、历史归档、离线验证与冻结；真实演化案例继续以 [M4 Smoke 报告](m4-real-provider-verified-evolution-smoke.md) 为准。

## 1. 受控整合

| Git 项目 | 实际版本 / 行为 |
|---|---|
| 整合前 main | `15f576fb5f723a9565c6361e0c95c1169ece6972`，工作区干净 |
| 成功开发分支 | `codex/m4-provider-diagnostics @ 9efee44518f9831cf8a06651db567e2bdf15c577`，工作区干净 |
| merge-base | `47ca4487210505529d6f4b4082052530c33c33d3` |
| 双方独有提交 | main：两份报告提交；开发分支：八份维护提交 |
| 合并方式 | 普通 `--no-ff` merge，ort，无冲突；保留双方历史 |
| merge commit | `bf949db02420e7a526e2a6864fd9544b3391abbe`，父提交为上述 main / 开发分支 |
| 新本地配置提交 / 最终生产验证基线 | `1830e67854f66db91f55d839e21fc654179c98e4` |

merge 实际整合 16 个文件、1,212 行新增 / 48 行删除。包含安全 Provider 诊断、字节进度、可选有界 SSE、短且原子创建的 `.git/v`、CodingProposal 单份编号源码表示、对应测试及架构 / ADR。早期 Git cleanliness 诊断和命令局部 longpaths 已在 merge-base 之前进入 main。

八份维护提交为 `f8e8827`、`a0a7784`、`dc598f4`、`1c34eb1`、`2b25744`、`f0fb494`、`3453ed8`、`9efee44`；详细行为、失败与回归见 [原报告 §3.3](m4-real-provider-verified-evolution-smoke.md#33-逐轮真实尝试与生产修正)。中间的整文件单 hunk 指引只留在历史里，最终代码是局部 hunk 与单份 `numberedLines + endsWithLf`，没有再优化 Prompt。

净 diff 检查确认：Domain、POM、依赖版本与模块关系没有变化；UnifiedDiffValidator 的接受条件保持原样，只增加固定类别与数字诊断。没有调试启动器、临时构建副本、日志或实验产物进入合并文件。merge 后生产树与成功分支一致，之后唯一生产资源新增为显式本地 profile。源码、数据库 schema、验证器和测试代码没有为本交付任务再作修改。

**Candidate B 没有合入 main。** 其 Python 命名提示业务改动仍保留在隔离 WorkingCopy 和 Git bundle 中。交付维护提交与历史 A→B 是两个事件；main 在本任务因授权维护合并而前进，不能继续把当前 main HEAD 写成历史 A。没有 reset、覆盖未提交工作、改写历史或 push；成功开发工作树继续保留。

## 2. 可迁移本地配置

新增 [`application-m4-local.yml`](../../backend/delveforge-app/src/main/resources/application-m4-local.yml)，通过 `--spring.profiles.active=m4-local` 启用：

- SSE true；connect / idle 90s、absolute total 300s。
- `max_tokens=98,304`；原始响应 32 MiB；单事件 64 KiB。
- Verification action 900s、invocation 1200s、输出 8 MiB、XML 上限 160；动作上限仍为 16。
- SQLite、Workspace、日志均使用独立可覆盖的本地位置；监听 `127.0.0.1`。没有旧 DB、UUID、revision 或临时 Smoke 根目录。

默认非流式 / 60s / 16,384 tokens / 8 MiB 保持不变。新 profile 复用成功参数，**不是最优默认值或新付费验证结果**；当前一个 Gateway 共用预算，所有 AI 阶段都会受影响。Repository Analysis、Step Understanding、Proposal 和 Scope 上限保持不变。凭据只从外部环境注入，未加入源码。

正常启动、可安全复制的配置、完整 M1–M4 API 顺序、画像 / 方向 / Step 显式确认、技术 Verification 和失败边界见 [M4 本地使用](../M4_LOCAL_USAGE.md)。当前范围为授权、受信任、根 Maven Wrapper 离线可构建和测试的小型 Java Git 仓库，不承诺任意项目一次成功。

## 3. 历史文件分类与归档

只读 inventory 覆盖仓库根目录与 `E:\df-m4-smoke`，包括隐藏 Git 元数据。九个既有历史目录共 **5,434 文件、1,064,819,790 字节**；全部原地保留，不删除业务数据库或历史证据。

| 分类 | 保留内容 | 实际处理 |
|---|---|---|
| A：最终与恢复证据 | `20261010-m4-provider/run9/evidence` 中实际 A→B diff、检查点、Verification XML、前后 SQLite 一致快照、原资产 / WorkingCopy bundles、manifest；成功执行器产物与原报告；历史关键 BLOCKED / UNKNOWN | 原位置保留；关键原件 SHA-256 前后核对 |
| B：文档引用材料 | 当前 validation / retrospective 与外部报告引用的 API、日志、探针、快照和历史报告 | 保留现有路径；没有移动这些材料 |
| C：根目录无引用历史日志 | 28 份 `target-m4-*.log` / `target-full-suite-*.log`，共 **22,000,372 字节**；均未跟踪且已忽略 | 移到外部 `20261010-delivery-closeout/root-build-logs/`，逐份移动前后 hash 相同；保留原路径→归档路径清单，没有删除 |
| 未足够确认的中间产物 | 外部历史构建副本、诊断 clone、缓存、启动器和中间文件 | 保留。没有为减少体积而进行不可恢复清理 |

归档前对每个根日志扫描 `docs`、README、AGENTS 与外部 `.md/.json/.txt` 文件名引用，均为零；tracked / ignored 检查在移动前再次执行。根目录现在无 `.log` 文件。现有 `.gitignore` 已覆盖 `*.log`、`logs/`、`data/`，无需添加重复规则；新 profile 将应用文件日志放进日志目录，本任务构建输出直接放在外部交付目录。

完整清单：[外部 inventory（含隐藏文件）](E:/df-m4-smoke/20261010-delivery-closeout/external-inventory-all-files.json)、[根日志路径和 hash 清单](E:/df-m4-smoke/20261010-delivery-closeout/root-log-archive-manifest.json)。初次不含隐藏文件的 inventory 也保留，以上完整清单作为最终文件计数依据。

## 4. 最终离线验证

验证直接在 `E:\develop\DelveForge` 上执行，没有再次创建生产构建副本，也没有付费 Provider / M1–M4 Smoke。最后生产源码与资源基线为 `1830e678`；后续交付提交只修改文档。

| 检查 | 实际结果 / 证据 |
|---|---|
| 聚焦回归 | 10 个测试类、10 XML、**111 tests，0 failures / errors / skipped**；包括 SSE、字节 / 期限、hunk、Git / `.git/v`、报告预算、API；`m4-local` 下现有 Spring 上下文与日志测试也通过 |
| 最终 Maven Wrapper `verify` | **PASS，1376 tests，123 XML，0 failures / errors / skipped**；2026-10-10 18:12:35–18:22:10，Maven 报告 9:33；外层堆 512 MiB，短 `java.io.tmpdir=E:\dft-closeout` |
| 模块统计 | Domain 216、Application 738、Infrastructure 250、App 172 |
| 独立仓库 | 新建任务拥有的微型 Java/Maven Git 仓库，真实 Wrapper 离线 `test`：1 项通过；登记前后 HEAD 和干净状态一致 |
| 打包应用 / 新库 | 最终 jar 启用 `m4-local`，独立新 DB / Workspace / 日志；Flyway V1–V12 全成功，SQLite `quick_check=ok` |
| 正常 API | connectivity、创建 / GET 空 UserProfile、注册 / GET 授权 SoftwareAsset 全成功；只监听 `127.0.0.1:18829`，监听者就是任务应用 |
| 新库边界 | 用户画像 1、资产 1；RepositoryProfile、Direction、Plan、Step、WorkingCopy 均 0，Workspace 为空。没有调用 AI 或生成任何历史演化状态 |
| 收尾 | 任务 Maven / 应用进程已结束；新库停止后通过 SQLite backup 保存一致快照；历史五份关键原件 hash 不变，实际 WorkingCopy 仍为干净 B |

聚焦命令使用 Wrapper `test`，显式选择 `DeepSeekAiGatewayAdapterTest`、`DeepSeekStreamingResponseTest`、`CodingProposalValidationTest`、`ExecutionPreparationUseCaseTest`、`GitEvolutionExecutionIntegrationTest`、`WorkingCopyProvisioningIntegrationTest`、`MavenVerificationCommandTest`、`VerificationReportBudgetTest`、`EvolutionStepPreparationApiIntegrationTest`、`DelveForgeApplicationTests`，启用 `m4-local` 并保留其它模块的无匹配测试处理。完整命令为 `mvnw.cmd "-Djava.io.tmpdir=E:\dft-closeout" verify`，未跳测试。

启动检查没有凭据，Provider 指向未监听的本机端点，并只调用无需 AI 的 API。它验证新的 DB / Workspace 配置和应用装配，**不证明第二个仓库已完成真实 M4**。本次 1376 是合并后的维护源码验证；历史 Candidate B 的 1346 是另一版本的生产 BUILD / TEST / DIFF_CHECK，不能相加或混用。

可复核输出：[聚焦日志](E:/df-m4-smoke/20261010-delivery-closeout/focused-regression.log)、[聚焦 XML 汇总](E:/df-m4-smoke/20261010-delivery-closeout/focused-tests.json)、[最终 verify 日志](E:/df-m4-smoke/20261010-delivery-closeout/final-verify.log)、[版本和执行时间](E:/df-m4-smoke/20261010-delivery-closeout/final-verify-run.json)、[123 XML 汇总](E:/df-m4-smoke/20261010-delivery-closeout/final-tests.json)、[534 个实际构建输入 hash](E:/df-m4-smoke/20261010-delivery-closeout/verified-build-inputs.json)、[启动结果](E:/df-m4-smoke/20261010-delivery-closeout/offline-startup-result.json)、[新库检查与快照](E:/df-m4-smoke/20261010-delivery-closeout/startup-database-check.json)、[历史完整性检查](E:/df-m4-smoke/20261010-delivery-closeout/final-integrity-check.json)。完整 XML 分别保存在同目录 `focused-xml/` 与 `final-xml/`。

## 5. 历史真实 PASS 与当前交付边界

历史 PASS 的执行器是 `9efee445`，成功时间 2026-10-10 13:56:56，execute 702.050 秒；不是本次整合后再次付费执行的结果。原 API、数据库与物理 Git 已证明：

| 历史对象 | 真实事实 |
|---|---|
| 源 A | `47ca4487210505529d6f4b4082052530c33c33d3` |
| 隔离 Candidate / Verified B | `6dfdc121fe2f61f1542d060fe56718ea6c3bb57e`；只以 A 为父提交 |
| WorkingCopy | `E:\dfw4\a706f7cf-eec8-43f5-a335-4bbe871c717a`；HEAD B，干净 |
| 生命周期 | Plan ACTIVE、Step SUCCEEDED、WorkingCopy EVOLVING，baseline/source=A，current/lastVerified=B |
| Candidate Verification | 实际 BUILD package、TEST test、DIFF_CHECK 全成功；122 XML、1346 tests，零失败 / 错误 / 跳过。没有声称在 B 上跑 verify goal |

历史运行使用上节相同成功预算，原始密钥和推理内容不进入本报告。新 profile 的配置与加载经离线检查，不等于默认配置或整合后运行产物已有第二次真实 M4 PASS。

**M4 DONE / FROZEN，M5 DEFERRED。** 后端功能开发暂停。尚存限制：

- 高预算作用于全部 AI 调用；同步流程耗时与 Provider 消耗较高，默认配置未取得同等真实成功证明。
- 原始 CodingProposal / Provider prompt、响应未完整持久化；历史最早 cleanliness 输出、最初 Provider 传输原因以及原报告标明的未分类失败仍为 UNKNOWN，合并不能补回它们。
- Windows Maven / 测试可以访问宿主文件系统；路径与环境守卫不构成 OS 沙箱，不用于任意不可信仓库。
- 当前只接受有限 Java/Maven / Git 技术 Verification；无证据适配的业务 Criteria 拒绝。M5 恢复尚未实现，失败 Candidate 和未解决检查点不能手工改成成功或绕过生命周期重试。
- 本地提交没有推送；远端可能尚未包含此次交付内容。

本任务没有发现需要改变 Domain 或削弱安全边界的交付阻塞；结论范围仍限于所执行的自动化验证与离线启动检查。

## 6. 外部证据索引

| 位置 | 内容 / 处理 |
|---|---|
| `E:\df-m4-smoke\20261008-direct1`、`20261008-direct2-retry`、`20261008-direct3-no-preconditions`、`20261008-local1` | 最早 Smoke 与客户端 / 契约失败；原地保留 |
| `E:\df-m4-smoke\20261009-direct4-corrected-client` | 修正客户端后的 provisioning BLOCKED；原地保留 |
| `E:\df-m4-smoke\20261009-provisioning-diagnosis` | 原历史失败未复现与 UNKNOWN 证据；原地保留 |
| `E:\df-m4-smoke\20261009-stabilization` | 五次尝试、Git 长路径 / cleanliness 与后续 Provider 阻塞；原地保留 |
| `E:\df-m4-smoke\20261009-m4-continuation` | 60s / 120s 对照、`.git/v` 修复、两次准备失败与快照；原地保留 |
| `E:\df-m4-smoke\20261010-m4-provider` | SSE / reasoning / hunk / 编号表示实验、完整维护分支验证、最终真实 PASS 和快照；原地保留 |
| `E:\df-m4-smoke\20261010-delivery-closeout` | 本交付的归档清单、111 项聚焦测试、最终 verify、离线启动与新数据库检查；与真实 Smoke 分开 |

仓库报告只引用必要结构信息；外部目录仍是受控原件存放处，未整体移入 Git。
