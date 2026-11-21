# 十四题能力评估

手工选择的 SWE-bench Multilingual 子集，每题一次有效试验。Pilot 为 4/6，Expansion 为 7/8，合计 11/14。合并后共有 11 份 Resolved、3 份 Unresolved。该样本的内部解决率仅描述这一组任务。

[Pilot 原始结果及基础设施无效尝试](../RESULTS.md) · [Expansion 独立结果](EXPANSION-RESULTS.md)

| 批次 | 任务 | 主要语言 | 能力 | 官方评分 |
|---|---|---|---|---|
| Pilot | `google__gson-1014` | Java | JSON 树读取 | Resolved |
| Pilot | `gin-gonic__gin-2755` | Go | HTTP 测试上下文 | Unresolved |
| Pilot | `google__gson-2479` | Java | 序列化适配器校验 | Unresolved |
| Pilot | `javaparser__javaparser-4538` | Java | AST 遍历 | Resolved |
| Pilot | `gin-gonic__gin-1957` | Go | HTTP 请求头绑定 | Resolved |
| Pilot | `vuejs__core-11915` | TypeScript | 模板解析 | Resolved |
| Expansion | `prometheus__prometheus-10720` | Go | PromQL 函数 | Resolved |
| Expansion | `vuejs__core-11739` | TypeScript | SSR hydration 与 CSS 变量 | Resolved |
| Expansion | `google__gson-2024` | Java | 字段命名策略 API | Resolved |
| Expansion | `caddyserver__caddy-4774` | Go | 服务器配置多地址绑定 | Resolved |
| Expansion | `preactjs__preact-3689` | JavaScript | Hooks 错误边界 API | Resolved |
| Expansion | `javaparser__javaparser-4561` | Java | 文本块的符号解析 | Resolved |
| Expansion | `prometheus__prometheus-11859` | Go | 持久化快照恢复 | Unresolved |
| Expansion | `caddyserver__caddy-5870` | Go | 管理 API 删除操作 | Resolved |

## 按主要语言汇总

| 分组 | 任务 | Resolved | Unresolved |
|---|---:|---:|---:|
| Java | 5 | 4 | 1 |
| Go | 6 | 4 | 2 |
| TypeScript | 2 | 2 | 0 |
| JavaScript | 1 | 1 | 0 |

## 按仓库汇总

| 分组 | 任务 | Resolved | Unresolved |
|---|---:|---:|---:|
| google/gson | 3 | 2 | 1 |
| gin-gonic/gin | 2 | 1 | 1 |
| javaparser/javaparser | 2 | 2 | 0 |
| vuejs/core | 2 | 2 | 0 |
| prometheus/prometheus | 2 | 1 | 1 |
| caddyserver/caddy | 2 | 2 | 0 |
| preactjs/preact | 1 | 1 | 0 |

## 按任务类型汇总

| 分组 | 任务 | Resolved | Unresolved |
|---|---:|---:|---:|
| 缺陷修复 | 8 | 5 | 3 |
| 功能扩展 | 5 | 5 | 0 |
| 行为修正 | 1 | 1 | 0 |

以主要实现语言作互斥分组；PromQL 的编辑器部分与 Preact 的类型声明等跨语言部分仍计在各自任务中。各能力的观测见上表。

十四题共 321 个请求、8,860,409 个报告 Token、430 次已执行工具、101.8 分钟 Agent 时间、6.5 分钟评分时间。合并实际美元成本未知，按 Expansion 报告的同一假设约 US$24.81。

候选曾按环境和可评分性筛选。每题只有一次有效运行，无法估计 k 大于 1 的 pass@k 或 pass^k，亦无法据此推断未见任务的成功率。Pilot 原有两次 Gson 基础设施无效记录和 Gin 产物恢复按原记录保留。
