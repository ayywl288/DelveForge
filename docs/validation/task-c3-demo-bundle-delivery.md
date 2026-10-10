# Task C3 — Git Bundle、Catalog 与首页交付

2026-10-11。[C1](task-c1-user-discovery-consistency.md) 与
[C2](task-c2-demo-build-readiness.md) 已分别提交。C3 只整理演示仓库交付材料和文档，
没有增加 DelveForge 功能、调用 Provider、执行演化或发布附件。

## 最终附件

完整包在 `E:\df-demo-pack\20261011`；三份 Bundle 位于 `bundles`。
源仓库在 `repositories`，最终还原目录在 `restored-final`。外部及 Git 中的
[manifest](../demos/manifest.json) 内容一致；Git 只跟踪 metadata / Catalog，不包含源码或 Bundle。

| 文件 | 大小（bytes） | SHA-256 | 最终 baselineRevision |
|---|---:|---|---|
| expense-tracker.bundle | 44325 | `1c7958066e491db2be350f75eed65dea8507add99555bcbb5a94635412019b66` | `afb14c051daa5a5cf00ebc7a2257a887fdaa6194` |
| todolist-api.bundle | 85949 | `d51a8383552977585e1f0a0491c434b89a57c18ce9bd7d4aa8990e61bc8f89da` | `6f032fae8e4859d063063e49bac8d3ca895b4888` |
| gs-rest-service.bundle | 19970 | `8552c907826ac305b2a8363ac19d08c26295144e6789f8d91c49a54b4487e38b` | `e38e4a1cc1f54c036d40dd111ab50d103b154c06` |

三份均由独立仓库的 `git bundle create --all` 生成，自包含原始导入与适配提交。
它们不是上游完整历史，更不是 DelveForge WorkingCopy 的 Candidate。
固定上游 revision、许可、Java target、license SHA 和原始导入 revision 均在 manifest。

## 还原时发现并修正的实际问题

第一轮 ExpenseTracker clone 干净；TodoList 的 fresh clone 显示 `M pom.xml`。
检查 `git ls-files --eol` 得到 index/worktree 均 CRLF，但新增属性要求 `text eol=lf`；
忽略行尾空白后的 POM diff 为空。Spring REST 后续 fresh clone 出现同样现象。
source 工作树在 C2 检查时显示干净，因此 C2 本机构建成功不能代替 fresh clone 检查。

在两个源仓库中仅移除 `/pom.xml text eol=lf`，保留原 POM blob 和业务代码，追加提交：

- TodoList：`6f032fae8e4859d063063e49bac8d3ca895b4888`。
- Spring REST：`e38e4a1cc1f54c036d40dd111ab50d103b154c06`。

没有重写 C2 历史，没有 reset / clean，没有放松 Git guard 或修改共享配置。
第一次失败 Bundle、状态输出及 clone 保留，最终 Bundle 重新生成。
主要失败证据为 `evidence/<demo>/initial-dirty-restore.bundle`、`initial-restore-status.txt`；
TodoList 首次 clone 在 `restored/todolist-api`，Spring 首次 clone 在
`evidence/gs-rest-service/initial-dirty-restore`。未把这些中间附件标作可发布版本。

## 实际验证

各 Bundle 验证完整性 → clone 至新目录 → HEAD 比对 → 工作树洁净检查 → 根 Wrapper
`-o verify` → Surefire XML 计数 → 再次工作树检查。离线运行使用 C2 已准备的依赖缓存，
不能推论没有缓存的新机器也能离线构建。

| 最终还原仓库 | Bundle/HEAD/初始工作树 | Maven 结果 | 构建后工作树 |
|---|---|---|---|
| expense-tracker | 全部通过 | BUILD SUCCESS；12 测试；14.084 秒 | 干净 |
| todolist-api | 全部通过 | BUILD SUCCESS；4 测试；13.935 秒 | 干净 |
| gs-rest-service | 全部通过 | BUILD SUCCESS；3 测试；6.423 秒 | 干净 |

失败、错误、跳过均为 0。验证运行于 Windows / JDK 21.0.12.1 / Maven 3.9.14。
最终 source 及 restored 两侧 HEAD 一致且干净；`src/main/java` 对原始导入没有 diff。

## 文档与交付边界

[Demo Catalog](../demos/README.md) 给出许可、实际已有能力、适配内容、两项未实现方向、
SHA 校验/clone/测试/注册的 PowerShell 示例，并说明构建与 AI 演化验证的区别。
[首页](../../README.md) 增加真实 M4 自举案例、待发布清单、后端环境变量配置与当前使用范围，
同步显式画像审阅 API；[Roadmap](../ROADMAP.md) 记录 Task C 收尾，不开启新里程碑。

没有可用公开下载 URL，所以首页标记“待发布”；未 push、未创建或发布 GitHub Release。
源码、许可、适配 patch、原/还原 XML 和关键失败证据继续留在受控外部目录，不将完整测试
日志、源码或任何凭据添加到 DelveForge Git。根目录未新增构建日志。

本次三个 Demo 只标记 **BUILD_READY**。需要用户手动体验真实 User Discovery 与跨仓库
分析/方向/规划，再在明确授权、检查 Scope/Criteria 后尝试真实演化；本任务没有证明任何
一个 Demo 的 CodingProposal、Candidate 或 Verified B。

Windows 宿主 Maven 不是 OS 沙箱，只使用可信仓库和合成数据。ExpenseTracker 真实邮件
未验证，TodoList 原 API 安全限制未重构，默认 AI 配置没有同等真实 M4 PASS。M5 仍暂缓，
不支持用手工改库、重发失败 Candidate 或 reset 的方式恢复。完成后等待用户验收，不再扩展功能。

## 外部证据索引

相对于 `E:\df-demo-pack\20261011`：

- `manifest.json`、`bundles/`、`licenses/`：最终清单、完整附件、原许可副本。
- `upstream/`：固定来源 clone 与 zip 导出。
- `repositories/<demo>/ORIGIN.json`、`DEMO.md`：原始导入、适配和局限。
- `evidence/<demo>/adaptation.patch`、`history.txt`：原始导入到最终 baseline 的完整适配。
- `evidence/<demo>/bundle-verify.txt`、`restore-check.json`：真实完整性、HEAD、工作树与计数。
- `evidence/<demo>/baseline-surefire/`、`restored-surefire/`：C2 与 C3 原始测试报告。
- `evidence/<demo>-baseline-verify.log`、`<demo>-restore-verify.log`：实际构建结果。
- `evidence/todolist-api-first-compile-failure.log`：新增测试最初转义错误。
- 上述 `initial-*` 文件/目录：还原洁净检查的失败证据，未删除。
