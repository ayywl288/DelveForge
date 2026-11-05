# 本地合并与开发环境清理

2026-11-05 收尾；清理前清单及执行日志在忽略目录 `target/local-cleanup-20261104/`。
本次仅维护本地仓库和环境，没有新产品 Agent Run、付费模型调用、产品行为修改或远端写操作。

## Git

- 起点：`codex/real-mvp-acceptance-20261104` / `ee8eca3becea8cfb4ce6faf52a2aab1249e6a5d9`。
- 原 main：`63104f63f88498c765487fabd5009a7d342c6216`，已经包含工程修正 `7a240af`。
- 审阅报告提交后，以普通 `--no-ff` 合并产生 `f0cf2a695d58fd0bd4aec28980292ceff834eaaa`；`7a240af` 和 `ee8eca3` 均可从 main 到达。
- 逐一确认所有提交已在 main、没有关联工作树或未完成工作后，以 `git branch -d` 删除四个本地分支：
  `codex/original-random-completion-20261104`、`codex/random-fullstack-lifecycle-fix`、
  `codex/real-mvp-acceptance-20261104`、`codex/recoverable-read-tool-errors`。
- 仅保留 main；只有根目录这一个 Git 工作树。无强制删除、历史改写、远端分支删除或 push。
- `37ead350afef9b84d8d9d3ad7a8d29a10d1a6115` 是 Petclinic 正式 WorkingCopy 的接受版本，未混作 DelveForge 提交。

## 文档和生成文件

- 审计原有 71 个文档及附属证据文件，没有完全相同的重复文件；未认定有可安全删除的冗余文档，本次删除文档数为 0。
- 保留四份核心文档、23 份 ADR、运行说明、最终 MVP 报告、固定 Candidate/Delivery 证据，以及不同历史失败、安全和完整性诊断记录；原有 71 个文件散列均未变。
- 添加简短的[验证索引](README.md)，区分当前验收入口与历史记录，没有新增归档管理机制。
- 真实删除如下三个目录，合计 3,319 个文件、785,074,803 字节，约 **748.7 MiB**：

| 目录 | 用途 | 删除字节数 |
|---|---|---:|
| `backend/delveforge-infrastructure/target/test-databases` | 测试代码创建的临时 SQLite 夹具 | 381,018,112 |
| `backend/delveforge-app/target/test-databases` | 测试代码创建的临时 SQLite 夹具 | 351,444,992 |
| `target/real-mvp-acceptance-20261104/runtime-libs` | 从应用包解出的诊断依赖，可重新解包 | 52,611,699 |

删除前核对无 Java/Python 后台进程使用；全部目标解析到本仓库内，无链接或嵌套 Git 仓库，删除后确认路径消失。定向回归未重新生成上述目录。
保留当前应用包、正常 Maven 构建输出、依赖缓存、所有 `data/` 产品状态、正式 WorkingCopy、交付证据和失败现场。
较旧的截图、诊断导出、历史用过的 JAR 及其他测试仓库没有足够依据全部认定为冗余，继续保留。

## Docker

检查时没有运行中的容器。按实际标签、镜像身份和容器引用逐一核对后，删除以下 **8 个**已被后续版本替代的 `delveforge-agent` 镜像，无强制删除：

- `baseline-aware-v8-jdk21`、`baseline-aware-v8-jdk17`
- `baseline-aware-runtime-v8-jdk21`
- `source-execution-v8-jdk21`、`source-execution-v8-jdk17`
- `random-runtime-v8-jdk21`
- `preflight-v8-final2-jdk21`、`preflight-v8-final2-jdk17`

保留 `read-tool-recovery-v8-jdk21` 当前工具链、`baseline-aware-runtime-v8-jdk17` 备用工具链，以及旧失败 Run 引用的 `random-runtime-v8-jdk17`。
容器删除 **0**，卷删除 **0**：原有 5 个停止容器和 58 个卷均保留。
其中两容器属于保留的失败 Run `ea9f4a76-daa0-4e9c-ad0b-bab3bbb16ec1`；其源码卷、准备现场和 Maven 卷不动。
MySQL、Redis、LibreTV 和其他项目镜像未动。共享依赖缓存保留，29 个缺少 DelveForge 归属标签的卷也保留；其余旧试验卷尚不能认定已无取证价值。

Docker 镜像数量从 23 降为 15，但 `system df` 报告 Images 仍约 10.18 GB、Build Cache 约 5.038 GB。
构建缓存仍引用相关层，**不把镜像删除计作额外释放磁盘空间**；本次没有清理全局缓存或压缩 Docker 虚拟磁盘。

## 清理后验证

- Maven Wrapper 定向回归：`AgentPatchCorrectionsTest` 5、`AgentToolActivityTest` 36、`CodingAgentServiceTest` 63、`DockerPatchPolicyTest` 41，共 **145 项通过，0 失败、0 错误、0 跳过**。均为确定性测试，未调用付费模型。
- 完整后端 `mvnw.cmd package -DskipTests` 成功；打包阶段跳过测试，测试结果来自上一项，没有重跑整个产品验收。
- 新应用包 SHA-256：`21c20e09694ab5c84b16ab9bddef9b81b3782c62a8013d4a44832ae1b351e76b`；三个内嵌模块与 reactor 产物一致，五个沙箱脚本与源码一致。重新打包改变了包散列，历史验收使用的包散列仍保留在原验收证据中。
- `data/delveforge.db`、`data/m4-local/delveforge.db` 和两次真实试验数据库全部 `PRAGMA quick_check=ok`，清理前后数据库文件 SHA-256 完全相同。
- 成功试验仍为 1 Run、1 Candidate、1 Delivery；原随机试验的 5 个失败 Run 及状态、费用计数和保留标志不变。
- 两个原始仓库及两个正式 WorkingCopy 的 1,028 个跟踪文件、Git index、HEAD/tree 和干净状态均与清理前一致。成功 WorkingCopy 仍为 `37ead350`，接受 tree 为 `47e3a21630bd232366fa20405d3343c9efb3a362`。
- `frontend/design/` 六文件及 `grilling/` 两文件散列完全相同。
- 当前 JDK 21 镜像在禁网、只读、非 root 临时容器中通过协议 v8、五脚本 SHA-256、Java 21 和 Node 22 检查；检查容器自动移除，原 5 容器的身份、挂载及状态和原 58 卷集合未变。JDK 17 仅保留备用，没有声称重新验证其完整执行流程。

后续若要进一步释放 Docker 空间，需要先界定可丢弃的构建缓存或旧试验卷；本次保留这些不确定材料。
清理不改变原 MVP 报告的严格流程验收 FAIL 结论。
