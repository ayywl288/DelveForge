# User Discovery 审阅资格并发修复（UD-FR-001）

日期：2026-10-28（Asia/Shanghai）。范围仅为最终独立审查的 Important / Blocking 并发发现。

## 状态

**本次范围结论：PASS（修复与回归）；待独立复审。** 完整后端验证已通过。
原 28016b5 的 REJECTED 结论不改写；本记录不替代独立复审，也不执行合并或推送。

## 对象与历史

- 工作树：`E:\develop\DelveForge-worktrees\user-discovery-conversation`。
- 分支：`feature/user-discovery-conversation`；开始 HEAD：`28016b5a72fbb9d4bf87a9fadda506a74ac89fae`。
- main / merge-base：`ff17d22c2dbf7b75397ed37df316886232c11d36`。
- 开始时功能工作树干净；主工作树原有 `frontend/design/`、`grilling/` 未跟踪目录不属于本任务。
- 最终独立审查对 28016b5 的 REJECTED 结论及复现材料原样保留在
  `E:\develop\DelveForge\target\final-independent-review-28016b5\`。
- 初次交付、独立验收、UD-IR-001/002 修复报告及真实会话 JSON 均保留原文。

## 根因与最小修正

审阅请求在事务外读取本版通过记录，随后复用缓存布尔值。另一请求可以针对同一画像版本
完成不足评估并清除记录，而不改变画像的 revision、状态、内容或 Evidence。原保存只核对
画像快照，无法发现资格撤销，仍进入 REVIEWING 并重新写入通过记录。

Application Port 新增 `saveReviewIfSufficient`，只用于复用通过结果的同版审阅保存。
SQLite Adapter 在该方法的同一事务内：先用已有条件 UPDATE 取得写锁并核对原始画像快照，
再检查同 ID + revision 的通过记录仍存在，最后保存审阅候选。标记已撤销则抛出既有
`UserProfileStateException`，由现有接口映射为 `409 CONFLICT`，不保存候选、不恢复标记。
该路径不再调用写入充分性标记的方法，也不会隐式重新调用模型。候选 ID / revision 必须
与原始快照一致，已有同版本内容检查继续防止改写快照。

有效标记仍可零模型调用进入审阅；无标记时的新评估路径继续通过原来的
`saveWithSufficiency` 原子保存 REVIEWING 与本次通过结果。`beginReview()` 仍在 Application
通过 Domain 操作执行，基础设施只提交候选；不新增状态、持久化表、迁移、依赖或评估历史框架。

涉及的生产代码为 `UserProfileRepository`、`AssessProfileSufficiencyUseCase` 和
`SqliteUserProfileRepository`；同步更新 Application 测试替身。DOMAIN_MODEL §6.1 补充
复用资格的原子有效性要求，ARCHITECTURE 记录实际提交边界，ROADMAP 链接本次阻塞修复。

## 复现与并发回归

新增 `UserProfileReadinessRaceIntegrationTest`，使用正式 Ask / Assess / Evaluator 用例、正式
Spring 事务代理和 SQLite / MyBatis / Flyway Adapter，数据库在隔离测试目录新建。
固定模型替身只提供解析合法的正面/负面响应，不访问网络。测试类没有外层事务。

每次交错均以独立画像执行：先通过正式提问用例建立 revision=4 的通过记录；代理仅在真实
`hasSufficientAssessment` 返回 true 后暂停审阅线程；另一正式提问请求完成不足评估并清除
标记；然后恢复旧审阅。除暂停点外，代理转发所有真实 Repository 操作。

- 修复前同一回归连续 3 次失败：旧审阅均成功，最终 REVIEWING、标记 true、revision=4、审阅模型调用 0。
- 修复后三次均抛出 `UserProfileStateException`：最终 EXPLORING、标记 false、revision=4、审阅模型调用 0。
- 断言完整六区、Evidence、revision 和状态与交错前相同；已读取对象没有被失败操作改写。
- 另覆盖有效缓存零模型调用、无标记的新正面评估一次模型调用，以及不能把旧版标记带到新 revision。
- 新增 6 项测试（3 次重复交错 + 3 项正常/边界路径）。既有断言未删除、禁用或放宽。

## 实际验证

| 执行 | 结果 |
| --- | --- |
| 修复前 `mvnw.cmd -pl backend/delveforge-infrastructure -am -Dtest=UserProfileReadinessRaceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 5 tests：3 failures，0 errors/skips；三次均复现旧资格恢复，另两项正常路径通过 |
| 修复后 `mvnw.cmd verify -Dtest=UserProfileReadinessRaceIntegrationTest,AssessProfileSufficiencyUseCaseTest,SqliteUserProfileRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS；41 tests（Application 12 / Infrastructure 29），0 failures/errors/skips；新增 6 项全部通过；13.159 秒 |
| `mvnw.cmd verify -Dtest=*UserProfile*,*Sufficiency*,*Discovery* -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS；375 tests（75 / 196 / 29 / 75），0 failures/errors/skips；19.410 秒 |
| `mvnw.cmd verify`（无筛选） | BUILD SUCCESS；1770 tests：1731 passed、39 skipped，0 failures/errors；12:29；2026-10-28 20:25:28 +08:00 完成 |
| `git diff --check`、历史报告 SHA-256 / Git 差异核对 | PASS；历史记录未改写 |

原始输出在本功能工作树忽略目录 `target/ud-fr-001/`：`reproduction.log`、
`focused-race.log`、`discovery-regression.log`、`full-backend.log`，以及
`full-backend-summary.json`、`skipped-tests.json`。

本次全量分模块：Domain 209、Application 846、Infrastructure 497（36 skipped）、
App 218（3 skipped）；Surefire XML 与日志计数一致。39 项跳过的测试名与原因同上一轮清单逐项一致，
完整明细见本次 `skipped-tests.json`。其中 36 项因下列显式环境开关未设置而跳过：

| 未启用环境开关 | 跳过数 |
| --- | --- |
| `DELVEFORGE_TASK2_E2E` | 1 |
| `DELVEFORGE_REAL_STEP` | 1 |
| `DELVEFORGE_AGENT_E2E` | 1 |
| `DELVEFORGE_AGENT_DOCKER` | 25 |
| `DELVEFORGE_AGENT_COMPATIBILITY` | 2 |
| `DELVEFORGE_SANDBOX_PROTOCOL_IMAGE` | 1 |
| `DELVEFORGE_SANDBOX_LEGACY_IMAGE` | 1 |
| `DELVEFORGE_REAL_BASELINE` | 1 |
| `DELVEFORGE_REPOSITORY_COMPATIBILITY_DOCKER` | 2 |
| `DELVEFORGE_AGENT_LIVE` | 1 |

另 3 项由公开仓库克隆验收的显式启用假设中止：

- `WorkingCopyActivationApiIntegrationTest.untouchedFinTrackPersistsActualProvenanceThroughNormalPreparationApi`：Fresh public clone acceptance is separately enabled。
- `RepositoryCompatibilityIntegrationTest.untouchedFinTrackIsAutomaticallyPreparedWithoutManualSourceChanges`：Fresh public clone acceptance is separate。
- `RepositoryCompatibilityIntegrationTest.genuinePublicRepositoriesReceivePinnedDiagnosticsWithoutHostBuilds`：Additional public clone matrix is separate。

本次新增六项及 User Discovery 专项均没有跳过。

## 证据范围与保留限制

本次没有修改前端，不重新运行前端测试、类型检查或构建；已有同版前端 276 项及类型检查、
构建证据是历史证据。UD-IR-001 / UD-IR-002 的相关后端回归包含在本次 375 项中。

本次未调用付费模型。此前真实会话 12 动作、21 次 Provider 调用、49,102 输入与 18,852 输出
Tokens 属于 28016b5 的既有证据，不作为本次新执行结果。固定模型并发测试验证资格提交边界，
不证明自然语言提取的普遍语义质量。

按最终独立审查裁定，同版不同请求语境的充分性波动为非阻塞观察；暂态条目明确纠正后已移除、
历史快照保留的质量限制继续保留，不扩展成新功能。无新的合并结论；修复提交等待独立复审。
