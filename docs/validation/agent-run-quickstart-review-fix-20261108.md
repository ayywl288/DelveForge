# Agent Run / Quick Start Review Fix

日期：2026-11-08。分支：`codex/agent-run-quickstart-refinement`。父提交：`02593d801d47972ab52f21192b51e5cd07445ad8`。
本报告随独立修复提交交付；完整提交哈希见交接回复或 `git log -1`。不合并、不推送、不重写历史。

## 修改文件与行为

| 文件（相对仓库根目录） | 修复内容 |
| --- | --- |
| `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/quickstart/QuickStartSources.java` | `.env.example` 使用预分配、有界 StringBuilder，最多 256 个不同变量、12000 字符（含截断标记）。追加前检查双重上限，不再 reduce 复制累计字符串。仅输出合法变量名和固定占位值；注释、所有取值丢弃。 |
| `frontend/src/views/QuickStartView.vue` | 来源区用 Vue 转义文本/code 节点显示，不进入 Markdown/链接解析器。反引号、Markdown 链接与 HTML 路径均保持字面文本；正文中的正常访问链接不受影响。已有来源存储格式无需迁移。 |
| `frontend/src/components/AgentResultSurface.vue` | 按 Candidate ID 创建 AgentTestEvidence 实例；切换 Candidate 即重置展开状态，包括两个 Candidate 的 evidence 文本完全相同的情况。 |
| `frontend/src/views/EvolutionView.vue` | Maven 覆盖只在当前 RepositoryProfile 的 techStack 明确包含 Maven 时显示和提交。Gradle-only、未知元数据不显示；已填后变为不适用也不发送旧值。JDK 自动默认与原手动入口不变。运行历史去除模型/工具调用数，选中 Run 的开发者详情仍可查看完整统计。 |
| `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/application/quickstart/QuickStartSourcesTest.java` | 增加接近 64 KiB、超过 3000 个变量的高密度示例，以及接近上限的长变量名示例；验证数量/字符限额、去重、截断标记，以及模型请求不含取值、注释和凭据样例。 |
| `frontend/tests/quick-start.test.ts` | 恶意路径包含单/双反引号、外部 Markdown 链接、强调符号、HTML；断言来源区没有链接、图片、脚本或强调元素，并保留路径原文。 |
| `frontend/tests/agent-result-components.test.ts` | Candidate 切换后开发者详情收起，原始 evidence 不再展开。 |
| `frontend/tests/agent-workflow.test.ts` | 未知/非 Java/Maven/Gradle 上下文、隐藏 Maven 值不提交、费用确认不变、历史统计在开发者详情中仍可访问。 |
| `docs/decisions/0026-factual-completion-and-plan-quick-start.md` | 更新局部限额及呈现说明，明确 Tree 元数据枚举性能限制。 |
| 本报告 | 记录验证结果与剩余限制。 |

不修改 AgentLoop、v8 完成合同、Shell/日志机制、沙箱、授权、全局秘密保护、Candidate 冻结及 Git/SQLite Delivery。Quick Start 仍绑定最终固定 revision，仅一次独立 AiGateway 调用。

## 验证

先添加回归：旧实现下后端 2 项新测试失败；前端 4 项测试失败，分别覆盖来源链接注入、Candidate 展开状态、Maven 设置和历史统计。修复后：

- 后端 Maven Wrapper 定向 `verify`：**53 项通过，0 失败、0 错误、0 跳过**。QuickStartSourcesTest 6、QuickStartServiceTest 5、DeterministicRepositorySecretPolicyTest 27、RepositoryContentPolicyTest 12、SqliteQuickStartStoreTest 1、AgentAcceptanceContextApiTest 2。
- 后端四模块构建和可执行 JAR 打包通过。为避开上一轮默认 JAR 被现有应用占用的问题，本轮直接使用 `target/quickstart-review-fix-build` 源码副本；逐文件核对其 Maven 源码与当前工作区一致，没有停止现有应用。
- 前端相关测试：**4 个文件，159 项通过**；覆盖 quick-start、agent-result-components、agent-workflow、agent-timeline。
- `pnpm --dir frontend typecheck`：通过。
- `pnpm --dir frontend build`：通过，101 modules。
- `git diff --check`：通过。
- 原有 **10 张截图保留**，逐张 SHA-256 与既有 manifest 一致；本次没有重新设计、覆盖或重拍截图。

前端首次测试启动被本机子进程权限阻止（spawn EPERM），允许正常启动子进程后完成上述回归；这不是测试断言失败。未运行完整后端/前端套件或 Docker 集成。本次付费模型、真实产品 AgentRun、Bookstore 启动/E2E 均 **NOT RUN**。

复现命令（仓库根目录；第一条使用本次保留的源码副本）：

```powershell
.\mvnw.cmd -f target/quickstart-review-fix-build/pom.xml verify '-Dtest=QuickStartSourcesTest,QuickStartServiceTest,DeterministicRepositorySecretPolicyTest,RepositoryContentPolicyTest,SqliteQuickStartStoreTest,AgentAcceptanceContextApiTest' '-Dsurefire.failIfNoSpecifiedTests=false'
pnpm --dir frontend test -- quick-start.test.ts agent-result-components.test.ts agent-workflow.test.ts agent-timeline.test.ts
pnpm --dir frontend typecheck
pnpm --dir frontend build
```

日志位于本地忽略目录 `target/review-fix-backend-red.log`、`review-fix-frontend-red.log`、`review-fix-backend-verify.log`、`review-fix-frontend-tests.log`、`review-fix-typecheck.log`、`review-fix-build.log`（后五项同属 `target/`）。旧截图清单为 `target/agent-run-quickstart-review/manifest.json`。

## 剩余限制

1. **Tree 元数据仍全量枚举。** 现有 `GitWorkspaceAdapter.listEntries()` 先执行完整递归 `git ls-tree`，再在内存按深度/路径过滤；Quick Start 还需排序和候选映射。读取次数、正文和模型上下文限额不限制这部分枚举时间/内存。大仓库性能限制已记录，本轮不改 WorkspaceReadPort 或其他消费者。
2. Maven 设置可见性依赖现有 RepositoryProfile；缺少 Maven 元数据时隐藏手动根目录覆盖，后台自动选择仍可工作。本轮不新增仓库探测。
3. 环境示例超过数量/字符限额时只输出前面可容纳的变量，并提示截断；既有秘密模式识别范围没有扩张。来源区安全修复不承担自然语言指南真实性验证。
4. 运行中的应用未更新或重启。需要使用新代码时，先按现有启动说明停止旧前后端，再执行 ` .\setup-delveforge.ps1 ` 重新构建并启动。

用户的 `codex-migration/`、`frontend/design/`、`grilling/` 及真实 Run/Bookstore 材料未修改。
