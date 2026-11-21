# SWE-bench Multilingual 六题基线

8 道预选题中有 6 道通过本机准入检查，涵盖 Java、Go 和 TypeScript。按每题一次有效试验统计，**6 个 Run 完成，6 份补丁获得官方评分，4 份 Resolved，2 份 Unresolved。单次有效试验的内部解决率为 4/6（66.7%）。**

| 任务 | 官方评分 | Run ID | 受评补丁 SHA-256 |
|---|---|---|---|
| google__gson-1014 | Resolved | `b1e1f4a6-94c2-48e1-8bb3-6630c7e65ca5` | `61212788f2aba8b97c2e227ab7c22c9ae59af25d9e73d36cf1597cd36787ee39` |
| gin-gonic__gin-2755 | Unresolved | `9dff1f9f-be6f-427e-a250-d43079c9c8fd` | `bd6e9ccd74cd02c1147ccf5c6e5ae4356e8469762c83124fc5f06515c3e45a75` |
| google__gson-2479 | Unresolved | `aedf6b09-2c93-46e5-b0ad-3ca9777a062b` | `8379b2022ae9d6f1f7f5ad98fdc45ed03bc5e0df23764578a38e0d749ff36eef` |
| javaparser__javaparser-4538 | Resolved | `9fbe82c8-85bd-4cc9-97b9-75ebf9f30473` | `279a6e03be86cc2b38bf3b6315ec885db200eb6f08164101f44173d12b7958e1` |
| gin-gonic__gin-1957 | Resolved | `964b4c55-7e59-4f52-9c58-359677a3e76b` | `620f4842131778d3254b33f9c63a539019dad65d72a793b302534fe6ca82b7e6` |
| vuejs__core-11915 | Resolved | `f37ae39d-bfb8-4a12-b1ac-829c0250e0d7` | `2b58cc7d3f2f99a5560071f4792340dacbb4b54a512aec2448a376b5d0358294` |

6 个有效 Run 合计 117 次模型请求，报告了 2,105,043 tokens。逐 Run 的实际美元账单未知。

## 分母与历史记录

候选题经过选择。Axios 4738 的参考补丁评分超时，Axios 5316 的基线缺少依赖，两题均暂缓。Gin 1957 的空补丁在编译阶段失败，准入保留了这项例外的官方日志。选题与准入限制了结论的适用范围，4/6 不能作为可与排行榜比较的严格 pass@1。旧版 Runner 生成的报告沿用 `observed pass@1` 标题，其按目录统计的分母见[评测说明](README.md)。

Gson 1014 的前两次执行分别因 Windows 检查点写入故障、宿主机重启而中断。两份部分补丁的官方结论均为 Unresolved，作为基础设施无效尝试单列。表中只计第三次完整执行。前两次的原始清单分别保存在本机 `target/eval-assets/trials/google__gson-1014/` 和 `target/gson2/target/eval-assets/trials/google__gson-1014/`。

Gin 2755 的 Agent 已完成，旧导出器在冻结产物时因 Git 忽略的未跟踪文件而中止。恢复记录用原 Trace 中的最终差异和恢复后的补丁分别回放，得到同一 Git tree `ea27f9d17ab444ec315395017da334bf2e2f98e7`。随后官方评分为 Unresolved。恢复新增 0 次模型请求，沿用原 Run，只计一次。

对同一道题，pass@k 衡量 k 次尝试中至少一次解决的概率，pass^k 衡量 k 次全部解决的概率。本实验每题只有一次有效观测，缺少估计 k > 1 所需的独立重复试验。不同题的结果、历史中断和同一产物的恢复均不能补足样本。

## 版本与证据核对

实际受评的产品基于 `f2c54db220f3673d20973bdf56978cc7925927f1` 加当时未提交的模型兼容改动。产品差异 SHA-256 为 `e0a9f80c29cc58b78374d237c7baf176787b9660193382f2f73d8a8e73d570fa`，受评 JAR 为 `02806076c2d8cc443df2d6e45c10d41865d4a225e8f021cfe089c07f92806fcc`。任务集版本为 `6e08cbcd763c869abd7aae4bcb9e1b3991180777`，官方评分器版本为 `02e7a74ffd0b707aab73d203fe87bdc7c76afc8e`。逐题基线提交与镜像摘要见[任务清单](suite.json)。

所有有效 Run 均使用 OpenAI Responses、`gpt-6.2-sol`、high、96 次模型请求、45 分钟、2 CPU、3072 MiB、并发 1。Gson 1014 和 Gin 2755 执行时的工具摘要为 `93b8813f6693b520a58a2dceb81e6200a6a38a726b55d2ad399a5b16ea3a5b35`。Gin 产物恢复和后四题使用修正后的工具，摘要为 `bd826fdd0417c98eae7d27beec2213e8c1b367f26bb6a4268cc770da64c50e7b`。两份冻结配置仅此字段不同。[修正后的完整冻结配置](baseline/suite-config.json)

前两行的 Run、Trace 和原始清单在本机 `target/gson3/target/eval-assets/trials/`。后四行在 `target/baseline-v2/target/eval-assets/trials/`。Gin 2755 的受评补丁、恢复凭据和官方评分位于后者的同级目录 `recovered/gin-gonic__gin-2755/`。以下 SHA-256 均按原文件字节计算。

| 本机文件（相对仓库根目录） | SHA-256 |
|---|---|
| `target/gson3/target/eval-assets/suite-config.json` | `46ccb6d8ef0e07ffa5b9df3cee76c2f17dcb14e6021a0d9ea5e692098bccefd1` |
| `target/baseline-v2/target/eval-assets/suite-config.json` | `b4d35ca081d631ef9b7cab2f21ea95f6b6888f85d3bf76a6b96236e2e3511491` |
| `target/baseline-v2/target/eval-assets/provenance.json` | `0843c5a667cd13a122326b1cbdb357506348b2b678ba83ac929e34e31136b62a` |
| `target/baseline-v2/target/eval-assets/baseline-metrics.json` | `7329d14abc3c6242fdb44354a2975448fb642aba7d2829556dc95763332a258d` |
| `target/baseline-v2/target/eval-assets/recovered/gin-gonic__gin-2755/recovery.json` | `b4b29ca061fe70f97a8d3a15e4598b2cbd1eaa31b79f32b97b334b9cf757c35b` |
| `target/baseline-v2/target/eval-assets/six-task-evidence.zip` | `fd9cb2598f35f4577f1ae91fee6dbf0599b266328bee85deb9f708fd848c748c` |
