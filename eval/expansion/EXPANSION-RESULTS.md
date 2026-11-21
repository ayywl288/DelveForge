# 八题 Expansion 实测

冻结的八题各执行一次，官方评分为 7 Resolved、1 Unresolved。8 次 Run 自主到达 COMPLETED。

同一固定 JAR、OpenAI Responses、gpt-6.2-sol/high，每题 96 个请求、45 分钟、2 CPU、3072 MiB、768 PID，并发 1。所有补丁相对各自基线回放后交由独立官方评分器。

| 任务 | Run 状态 | 官方评分 | 请求 | Token | 已执行工具 | Agent / 评分秒数 | Trace 与评分 |
|---|---|---|---:|---:|---:|---|---|
| `prometheus__prometheus-10720` | COMPLETED | Resolved | 20 | 659,699 | 47 | 361.2 / 22.3 | [Trace](../../target/expansion-eight/trials/prometheus__prometheus-10720/trace.json) · [评分](../../target/expansion-eight/trials/prometheus__prometheus-10720/grader/official.json) · [归档](../../target/expansion-eight/archives/prometheus__prometheus-10720.zip) |
| `vuejs__core-11739` | COMPLETED | Resolved | 18 | 553,966 | 26 | 493.4 / 20.9 | [Trace](../../target/expansion-eight/trials/vuejs__core-11739/trace.json) · [评分](../../target/expansion-eight/trials/vuejs__core-11739/grader/official.json) · [归档](../../target/expansion-eight/archives/vuejs__core-11739.zip) |
| `google__gson-2024` | COMPLETED | Resolved | 14 | 271,279 | 21 | 433.9 / 32.9 | [Trace](../../target/expansion-eight/trials/google__gson-2024/trace.json) · [评分](../../target/expansion-eight/trials/google__gson-2024/grader/official.json) · [归档](../../target/expansion-eight/archives/google__gson-2024.zip) |
| `caddyserver__caddy-4774` | COMPLETED | Resolved | 22 | 584,067 | 31 | 153.1 / 22.2 | [Trace](../../target/expansion-eight/trials/caddyserver__caddy-4774/trace.json) · [评分](../../target/expansion-eight/trials/caddyserver__caddy-4774/grader/official.json) · [归档](../../target/expansion-eight/archives/caddyserver__caddy-4774.zip) |
| `preactjs__preact-3689` | COMPLETED | Resolved | 21 | 477,806 | 37 | 377.2 / 22.1 | [Trace](../../target/expansion-eight/trials/preactjs__preact-3689/trace.json) · [评分](../../target/expansion-eight/trials/preactjs__preact-3689/grader/official.json) · [归档](../../target/expansion-eight/archives/preactjs__preact-3689.zip) |
| `javaparser__javaparser-4561` | COMPLETED | Resolved | 57 | 2,322,667 | 58 | 654.8 / 49.1 | [Trace](../../target/expansion-eight/trials/javaparser__javaparser-4561/trace.json) · [评分](../../target/expansion-eight/trials/javaparser__javaparser-4561/grader/official.json) · [归档](../../target/expansion-eight/archives/javaparser__javaparser-4561.zip) |
| `prometheus__prometheus-11859` | COMPLETED | Unresolved | 33 | 1,448,043 | 41 | 308.7 / 23.7 | [Trace](../../target/expansion-eight/trials/prometheus__prometheus-11859/trace.json) · [评分](../../target/expansion-eight/trials/prometheus__prometheus-11859/grader/official.json) · [归档](../../target/expansion-eight/archives/prometheus__prometheus-11859.zip) |
| `caddyserver__caddy-5870` | COMPLETED | Resolved | 19 | 437,839 | 23 | 211.2 / 22.4 | [Trace](../../target/expansion-eight/trials/caddyserver__caddy-5870/trace.json) · [评分](../../target/expansion-eight/trials/caddyserver__caddy-5870/grader/official.json) · [归档](../../target/expansion-eight/archives/caddyserver__caddy-5870.zip) |

## 用量与证据

本批合计 204 个模型请求、6,755,366 个报告 Token、284 次已执行工具。每一条工具回执、原始测试输出和冻结补丁均可在对应压缩包中核对。

用户确认八题不设累计美元上限。工作台仅提供聚合 Token，实际账单未知。在普通短上下文、无缓存、90% 输入和 10% 输出的示例中，按每百万 Token 输入 2 美元、输出 10 美元估算，本批约 US$18.92。缓存、长上下文和实际输入输出比例会改变结果。[官方价格](https://developers.openai.com/api/docs/models/gpt-6.2-sol)

[逐题请求、工具、失败项、Run ID、补丁和归档摘要](../../target/expansion-eight/reports/expansion-metrics.json) · [隔离与身份核验](../../target/expansion-eight/preflight.json) · [清理对象 ID](../../target/expansion-eight/storage/removal-plan.json) · [冻结清单](final-eight.json)

Caddy 5870 的官方目标为 TestUnsyncedConfigAccess，没有 P2P；其结果只支持被测配置访问行为，完整的 HTTP 响应链仍未由该题评分验证。Gson 2024 和 Caddy 4774 的固定清单同样没有 P2P。

## 开发侧验证记录

- Prometheus 10720 跑通了 PromQL parser 和独立引擎测试。完整 promql 包依赖下载遇到 HTTP 403。
- Vue 11739 在修复前复现 hydration 测试失败，修复后六个测试文件的 143 项测试通过，类型检查退出码为 0。
- Gson 2024 的默认 Maven 路径受旧构建配置与 JDK 21 影响。改用 Surefire 跑命名策略相关测试，27 项通过。
- Caddy 4774 的 Caddyfile 基础解析测试通过。HTTP 适配器及集成测试的依赖下载被拒绝。
- Preact 3689 的浏览器测试日志记录 960 项完成，完整构建缺少 iltorb.node 原生模块。
- JavaParser 4561 的 JavaParserFacadeResolutionTest 在 JDK 21 下运行 17 项，失败、错误和跳过均为 0。
- Prometheus 11859 的 chunkenc、record 包测试通过，完整 TSDB 测试依赖下载返回 403。独立评分环境成功应用补丁，TestSnapshotAheadOfWALError 期望 not found、实际 nil，TestSnapshotError 期望计数 1、实际为 0。一个目标项与一个回归项均失败。
- Caddy 5870 在开发环境新增 HTTP 级回归用例，根包 go test -count=1 . 通过。全仓测试依赖下载返回 403。官方仅运行既定的配置访问测试。

工作台测试与独立评分容器分别记录。每题的命令、退出码及模型请求诊断可在 [本地诊断目录](../../target/expansion-eight/diagnostics) 查阅。

## 磁盘与复核

准备期间移除了 10 个已停止的免费准入容器及 7 个暂缓题镜像，保留当时全部 155 个卷。Docker 镜像占用从 43.38 降至 37.91 GB，容器占用从 3.007 降至 2.951 GB。E 盘清理前后均为 21.44 GiB 可用，VHDX 实际分配均为 59,665,022,976 字节。没有进行 VHDX 压缩。每题归档后仅移除了其四个临时容器，保留独立工作副本与恢复证据。

29 项免费 Runner 单元测试通过。Vue 的依赖链接和 JavaParser 的 Windows 长路径产生过 Git 警告；冻结导出和 Git tree 回放核对成功。Pilot 与两轮准入记录按原有校验程序复核。
