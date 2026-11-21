# SWE-bench Multilingual 扩展准入记录

14 道新候选中，8 道取得本轮准入。推荐新增：`prometheus__prometheus-10720`、`vuejs__core-11739`、`google__gson-2024`、`caddyserver__caddy-4774`、`preactjs__preact-3689`、`javaparser__javaparser-4561`、`prometheus__prometheus-11859`、`caddyserver__caddy-5870`。暂缓题继续保留在候选清单。

工作区为 main，HEAD `673582a4cb7a96c893955b05fdd115f41df72a1b`。本轮仅新增 eval/expansion 内的报告与[固定候选清单](candidates.json)。原八项注册表、Runner、Agent、ADR、官方 grader 及已有试验记录保持原状。候选清单逐题保存完整镜像 SHA-256、linux/amd64 配置核验、七份官方资产摘要、F2P/P2P 名单及参考补丁文件。

任务版本 `6e08cbcd763c869abd7aae4bcb9e1b3991180777`，评分器 `02e7a74ffd0b707aab73d203fe87bdc7c76afc8e`。官方工作流先在固定镜像读取 /testbed 的 Git HEAD，再由 run_instance 运行原测试。每个对照记录保留评分客户端镜像 ID、独立运行 ID 和时间。原始评分和日志保存在 E 盘。

## 准入矩阵

R = 官方 Resolved，U = 官方 Unresolved。导入通过表示 Workbench 通过 Project API 导入后重新核验 Git 对象集合，并导出零字节差异。参考补丁只在宿主临时副本回放。未执行的格子没有评分。增删行来自参考补丁，包含文档。

| 候选 | 语言 / 能力 | 文件 / 增删行 | F2P/P2P | 基线和导入 | No-op / Oracle | 结论 |
|---|---|---:|---:|---|---|---|
| `prometheus__prometheus-10720` | Go＋TypeScript · 查询语言功能 | 7 / +37 −2 | 2/10 | 通过 | U / R | 准入 |
| `preactjs__preact-4436` | JavaScript · 组件生命周期功能 | 2 / +19 −4 | 2/24 | 前置拒绝 | 未执行 / 未执行 | 暂缓 |
| `projectlombok__lombok-3350` | Java · 代码生成功能 | 4 / +8 −4 | 1/2 | 通过 | U / R | 暂缓 |
| `vuejs__core-11739` | TypeScript · 跨包缺陷修复 | 4 / +18 −14 | 1/26 | 通过 | U / R | 准入 |
| `google__gson-2024` | Java · 库 API 扩展 | 1 / +23 −5 | 3/0 | 通过 | U / R | 准入 |
| `caddyserver__caddy-4774` | Go · 服务配置功能 | 2 / +24 −5 | 1/0 | 通过 | U / R | 准入 |
| `preactjs__preact-3689` | JavaScript/TypeScript · Hooks API 扩展 | 3 / +8 −7 | 1/3 | 通过 | U / R | 准入 |
| `facebook__docusaurus-9183` | TypeScript · 站点渲染功能 | 3 / +18 −5 | 1/35 | 前置拒绝 | 未执行 / 未执行 | 暂缓 |
| `nushell__nushell-13831` | Rust · CLI 功能 | 1 / +46 −5 | 1/0 | 通过 | 未执行 / 未执行 | 暂缓 |
| `tokio-rs__tokio-6724` | Rust · 异步 I/O 功能 | 1 / +10 −2 | 1/2 | 通过 | 未执行 / 未执行 | 暂缓 |
| `javaparser__javaparser-4561` | Java · 符号解析修复 | 1 / +18 −4 | 1/1 | 通过 | U / R | 准入 |
| `prometheus__prometheus-11859` | Go · 持久化恢复修复 | 1 / +38 −11 | 1/1 | 通过 | U / R | 准入 |
| `tokio-rs__tokio-4867` | Rust · 并发状态修复 | 1 / +48 −75 | 1/22 | 通过 | 未执行 / 未执行 | 暂缓 |
| `caddyserver__caddy-5870` | Go · HTTP 管理 API 修复 | 1 / +14 −2 | 1/0 | 通过 | U / R | 准入 |

## 环境、选题与可推广范围

开发侧对一个实际隔离项目验证了固定镜像、内部网络、2 CPU、3072 MiB、768 PID 和生效的代理。JDK 21/Maven、Node 22/npm/pnpm 已有；Go、Ant、Rust/Cargo、Chrome 和 Yarn 没有预装。Go 安装包、Go module/sum、指定 pnpm 与 Chrome 包、Maven Central 和 Ubuntu 源可达。Lombok 的默认引导源及两个 Ivy 源返回 403。Rust 三题没有提交 vendor，Rust 分发、crates 索引与包域均返回 403，即使通过发行版安装编译器也仍缺依赖。逐域 CONNECT 和上游状态见[实测回执](../../target/xad/dev.json)。

开发侧没有逐题做整仓冷构建，故应用完整构建及 45 分钟内完成仍未验证。官方容器的 Oracle 只证明评分侧构建/测试路径可用。筛选以任务要求、干净导入、补丁回放、官方对照和已知开发依赖阻断为依据；没有参考模型成绩。两道基线拒绝题和三道 Rust 题具有独立的前置阻断，避免为它们专门拉取昂贵评分镜像。

Pilot 六题单列：Gson 1014、JavaParser 4538、Gin 1957、Vue 11915 为 Resolved，Gin 2755、Gson 2479 为 Unresolved。既有 4/6 内部解决率只描述 Pilot。原两道 Axios 的暂缓记录继续独立保存。[原始六题结果](../RESULTS.md)

新增候选把范围从错误修复扩展到 API、配置、生成代码、组件生命周期和引擎/编辑器协作。两个 Prometheus 任务分别考查语言扩展和 WAL/快照恢复；两个 Caddy 任务分别考查监听配置和管理 API。Gson 新枚举和 JavaParser 文本块属于较局部改动，可作为较窄对照。并发、Rust CLI 和跨入口权限目前应视为候选能力，不能据静态题面算入已验证的覆盖。成功导入的仓库有 260 至 2581 个跟踪文件，包含独立库、多包前端、服务端和多模块 Maven 项目；这些数字不衡量难度。

冻结预算保持 OpenAI Responses、gpt-6.2-sol/high、96 请求、45 分钟、2 CPU、3072 MiB、768 PID、并发 1。当前 Runner 的 Multilingual 核心导入、导出和评分接口可复用；默认 CLI 仍读取原八题 suite.json。本次在独立证据根目录使用同一实现装载扩展映射。正式 Trial 前须确定单独套件、锁定通过的任务与工作目录并另行取得付费授权。Pro V2 单列，见[可行性报告](PRO-V2.md)。

## 下载与保存

Docker Desktop 配置及实际文件均指向 E:/develop/Docker-images/DockerDesktopWSL/disk/docker_data.vhdx。拉取 9 个镜像，清单压缩层合计 4.87 GiB（含共享层）；pull.log 的 Download complete 对应 18 个去重压缩层，共 2,281,765,445 字节（2.13 GiB）。后一项按实际下载完成事件计，不含协议头、重传、Git 或少量元数据流量。Git 压缩包当前保留 0.04 GiB；未采集主机网卡字节计数。没有拉取 Pro 镜像层。

拉取前至收尾，E 盘可用空间 33.20 → 24.07 GiB，Docker 数据 VHDX 文件长度 42.94 → 52.07 GiB。本轮 target/xad 的逻辑文件量约 0.33 GiB。卷空闲空间可能受到其他进程影响，VHDX 长度也包含可重用空间。[磁盘记录](../../target/xad/storage.json)

基线、缓存和证据保留于 E 盘。独立控制库的 conversation、run 记录均为 0。没有模型请求、付费云、正式试验、提交或推送。对 Pilot 归档的 249 个条目逐一与原件核对，两个 Axios 的准入及对照文件各有前后摘要。[保护校验](../../target/xad/preservation.json)

## 本机复核命令

最终交叉校验重新读取了 12 个导入工作副本、18 份正式对照报告、逐项证据哈希和四套 Pro 文件。独立控制库中 conversation、run、model_attempt 均为 0。核验命令为 `python target/xad/verify.py`。用于探测的 18949 服务及五个本轮专属容器已停止，卷、原件和下载缓存保留。

首次拉取后，临时审查脚本将本机 Docker 返回的镜像 ID 与配置摘要作比较，因二者标识不同，在评分前停止了该步。本轮随后按仓库清单摘要、平台及容器内基线 HEAD 校验，复用了刚下载的层。每道实际评分的候选各有一个 No-op 和一个 Oracle 运行 ID，官方评分器未改动。

从仓库根目录运行。前两条重新读取固定 Git 对象、原有证据和镜像回执。其余命令只查看本次已执行的对照和代理检查。

```powershell
python target/xad/static.py
python target/xad/manifest.py
Get-Content target/xad/controls.log
Get-Content target/xad/dev.json
Get-ChildItem target/xad/checks -Recurse -Filter official.json
git status --short --branch
```

本轮执行入口为 python target/xad/base.py、imports.py、replay.py、dev.py、dev_deps.py 和 grades.py（脚本均位于 target/xad）。metadata.py 读取固定任务原件和公开镜像标签，platforms.py 核验按摘要取回的镜像配置。按原评分实现与回执复原的 argv 见 [命令记录](../../target/xad/command-records.json)，各题的 invocation.json 保存评分 Run ID、耗时与退出码；client.log 和 logs/evaluation/.../test_output.txt 保存原始输出。独立服务位于本机 18949，复核草案不会启动 Trial。

## 逐题证据

### prometheus__prometheus-10720

无参或时间向量返回 UTC 年中日；引擎、解析器、浏览器编辑器及文档协作。 父测试和 functions.test 子测试有包含关系；覆盖时间边界，未直接验证编辑器补全。

仓库 `prometheus/prometheus`，基线 `89de30a0b754f4eb54fb543037683f3db6cda109`。参考改动：`docs/querying/functions.md`、`promql/functions.go`、`promql/parser/functions.go`、`web/ui/module/codemirror-promql/src/complete/promql.terms.ts`、`web/ui/module/codemirror-promql/src/promql.ts`、`web/ui/module/codemirror-promql/src/types/function.ts`、`web/ui/module/lezer-promql/src/promql.grammar`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。Go 1.23.8 / 模块代理；前端仍需 Node 依赖。

证据：[基线](../../target/xad/baseline/prometheus__prometheus-10720.json) · [镜像清单](../../target/xad/images/prometheus__prometheus-10720.json) · [对照记录](../../target/xad/checks/prometheus__prometheus-10720/controls.json) · [Workbench 导入](../../target/xad/imports/prometheus__prometheus-10720.json) · [宿主回放](../../target/xad/checks/prometheus__prometheus-10720/replay.json)

noop：FAIL_TO_PASS 0 通过 / 2 失败、PASS_TO_PASS 10 通过 / 0 失败。[官方报告](../../target/xad/checks/prometheus__prometheus-10720/noop/official.json) · [测试日志](../../target/xad/checks/prometheus__prometheus-10720/noop/logs/evaluation/df-72824f21a5fd4a1cbc2809e942bafe34/delveforge/prometheus__prometheus-10720/test_output.txt)

oracle：FAIL_TO_PASS 2 通过 / 0 失败、PASS_TO_PASS 10 通过 / 0 失败。[官方报告](../../target/xad/checks/prometheus__prometheus-10720/oracle/official.json) · [测试日志](../../target/xad/checks/prometheus__prometheus-10720/oracle/logs/evaluation/df-511f3b316d694d66b7134150f1e1b994/delveforge/prometheus__prometheus-10720/test_output.txt)

### preactjs__preact-4436

回调 ref 可以返回清理函数；卸载及更换 ref 时调用，保持旧 ref 语义。 两个浏览器断言直接检查清理时机，原有 ref 用例提供回归。

仓库 `preactjs/preact`，基线 `db0f4f2e7a2338ea40050f623f05505a798fc1a4`。参考改动：`src/diff/index.js`、`src/internal.d.ts`。

**暂缓。** 静态已核；基线拒绝；只核官方清单/平台。基线的 benchmarks 条目被现有校验拒绝；不能删除它来制造干净导入。 固定基线含 Git 子模块（160000）。

证据：[基线](../../target/xad/baseline/preactjs__preact-4436.json) · [镜像清单](../../target/xad/images/preactjs__preact-4436.json) · [对照记录](../../target/xad/checks/preactjs__preact-4436/controls.json)

### projectlombok__lombok-3350

Jakarta 空值注解的预置配置与生成参数的注解复制，涉及配置、处理器和文档。 官方 Delombok 快照使用 jakarta 预置、构造函数及 With；题面的 CUSTOM＋Builder 示例未由该快照直接执行。 原始 JUnit 输出中 No-op 有 5 项失败，Oracle 仍有 4 项失败；声明的 F2P/P2P 在 Oracle 全部通过。

仓库 `projectlombok/lombok`，基线 `984cd1d5cf0af7263efaf167dc308ab59406cd68`。参考改动：`src/core/lombok/ConfigurationKeys.java`、`src/core/lombok/core/configuration/NullAnnotationLibrary.java`、`src/core/lombok/core/handlers/HandlerUtil.java`、`website/templates/features/configuration.html`。

**暂缓。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。JDK/Maven 可用；Ant 未预装，基线未带 ivyplusplus.jar，默认下载地址和 Ivy 的两个仓库在实际代理上均为 403。 开发环境默认 Ant 引导下载被拒；Delombok 快照也未直接验证题面 Builder 用例。

证据：[基线](../../target/xad/baseline/projectlombok__lombok-3350.json) · [镜像清单](../../target/xad/images/projectlombok__lombok-3350.json) · [对照记录](../../target/xad/checks/projectlombok__lombok-3350/controls.json) · [Workbench 导入](../../target/xad/imports/projectlombok__lombok-3350.json) · [宿主回放](../../target/xad/checks/projectlombok__lombok-3350/replay.json)

noop：FAIL_TO_PASS 0 通过 / 1 失败、PASS_TO_PASS 2 通过 / 0 失败。[官方报告](../../target/xad/checks/projectlombok__lombok-3350/noop/official.json) · [测试日志](../../target/xad/checks/projectlombok__lombok-3350/noop/logs/evaluation/df-b1832bb2ef7d46b485f0468439ca7627/delveforge/projectlombok__lombok-3350/test_output.txt)

oracle：FAIL_TO_PASS 1 通过 / 0 失败、PASS_TO_PASS 2 通过 / 0 失败。[官方报告](../../target/xad/checks/projectlombok__lombok-3350/oracle/official.json) · [测试日志](../../target/xad/checks/projectlombok__lombok-3350/oracle/logs/evaluation/df-c6b8ea0d43b2400097dbd0f3cc07bb56/delveforge/projectlombok__lombok-3350/test_output.txt)

### vuejs__core-11739

父组件 CSS v-bind 和子组件内联样式组合时避免 SSR hydration 误报，涉及编译器、运行时及 shared。 检查带点号的 CSS 变量名不触发告警；运行时用例未完整编译题面双 SFC。

仓库 `vuejs/core`，基线 `cb843e0be31f9e563ccfc30eca0c06f2a224b505`。参考改动：`packages/compiler-sfc/src/script/utils.ts`、`packages/compiler-sfc/src/style/cssVars.ts`、`packages/runtime-core/src/hydration.ts`、`packages/shared/src/escapeHtml.ts`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。Node 和 pnpm 可用，基线固定 pnpm 9.9.0，npm 包地址可达。

证据：[基线](../../target/xad/baseline/vuejs__core-11739.json) · [镜像清单](../../target/xad/images/vuejs__core-11739.json) · [对照记录](../../target/xad/checks/vuejs__core-11739/controls.json) · [Workbench 导入](../../target/xad/imports/vuejs__core-11739.json) · [宿主回放](../../target/xad/checks/vuejs__core-11739/replay.json)

noop：FAIL_TO_PASS 0 通过 / 1 失败、PASS_TO_PASS 26 通过 / 0 失败。[官方报告](../../target/xad/checks/vuejs__core-11739/noop/official.json) · [测试日志](../../target/xad/checks/vuejs__core-11739/noop/logs/evaluation/df-f4287ddd1d64403cbcaf1746a9160785/delveforge/vuejs__core-11739/test_output.txt)

oracle：FAIL_TO_PASS 1 通过 / 0 失败、PASS_TO_PASS 26 通过 / 0 失败。[官方报告](../../target/xad/checks/vuejs__core-11739/oracle/official.json) · [测试日志](../../target/xad/checks/vuejs__core-11739/oracle/logs/evaluation/df-e3841acf73b541d487c0e04b0ab90ab3/delveforge/vuejs__core-11739/test_output.txt)

### google__gson-2024

新增大写下划线字段命名策略，覆盖序列化与反序列化；变更集中在一个枚举。 三个目标用例，显式回归为零；与 Pilot Gson 同仓，功能任务不同。 本机 No-op 在编译官方测试时缺少 UPPER_CASE_WITH_UNDERSCORES；报告把三个目标列为失败，不能据此声称三个测试方法均已执行。

仓库 `google/gson`，基线 `16b42ff5805074126c2e5484450c182773e408a2`。参考改动：`gson/src/main/java/com/google/gson/FieldNamingPolicy.java`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。官方使用 JDK 11；开发环境 JDK 21/Maven，可访问 Maven Central。

证据：[基线](../../target/xad/baseline/google__gson-2024.json) · [镜像清单](../../target/xad/images/google__gson-2024.json) · [对照记录](../../target/xad/checks/google__gson-2024/controls.json) · [Workbench 导入](../../target/xad/imports/google__gson-2024.json) · [宿主回放](../../target/xad/checks/google__gson-2024/replay.json)

noop：FAIL_TO_PASS 0 通过 / 3 失败、PASS_TO_PASS 0 通过 / 0 失败。[官方报告](../../target/xad/checks/google__gson-2024/noop/official.json) · [测试日志](../../target/xad/checks/google__gson-2024/noop/logs/evaluation/df-7de3550a99bd43c3afec631297ca8b57/delveforge/google__gson-2024/test_output.txt)

oracle：FAIL_TO_PASS 3 通过 / 0 失败、PASS_TO_PASS 0 通过 / 0 失败。[官方报告](../../target/xad/checks/google__gson-2024/oracle/official.json) · [测试日志](../../target/xad/checks/google__gson-2024/oracle/logs/evaluation/df-5c05b13fc6ae416bad03fa4a4c523481/delveforge/google__gson-2024/test_output.txt)

### caddyserver__caddy-4774

全局 default_bind 接受多个地址并保留站点级选择，配置解析与监听地址适配协作。 一个适配器快照断言生成的 JSON，未实际启动双栈监听；显式回归为零。

仓库 `caddyserver/caddy`，基线 `f7be0ee10131f25620a2f64af7e3ded43eae2049`。参考改动：`caddyconfig/httpcaddyfile/addresses.go`、`caddyconfig/httpcaddyfile/options.go`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。Go 1.18.10 安装包和模块代理可达。

证据：[基线](../../target/xad/baseline/caddyserver__caddy-4774.json) · [镜像清单](../../target/xad/images/caddyserver__caddy-4774.json) · [对照记录](../../target/xad/checks/caddyserver__caddy-4774/controls.json) · [Workbench 导入](../../target/xad/imports/caddyserver__caddy-4774.json) · [宿主回放](../../target/xad/checks/caddyserver__caddy-4774/replay.json)

noop：FAIL_TO_PASS 0 通过 / 1 失败、PASS_TO_PASS 0 通过 / 0 失败。[官方报告](../../target/xad/checks/caddyserver__caddy-4774/noop/official.json) · [测试日志](../../target/xad/checks/caddyserver__caddy-4774/noop/logs/evaluation/df-ad9b5783b9594224978832a6dc16a1cb/delveforge/caddyserver__caddy-4774/test_output.txt)

oracle：FAIL_TO_PASS 1 通过 / 0 失败、PASS_TO_PASS 0 通过 / 0 失败。[官方报告](../../target/xad/checks/caddyserver__caddy-4774/oracle/official.json) · [测试日志](../../target/xad/checks/caddyserver__caddy-4774/oracle/logs/evaluation/df-cef15a5f10fe40e0b81b1e21f39ee190/delveforge/caddyserver__caddy-4774/test_output.txt)

### preactjs__preact-3689

将组件捕获错误时的第二个信息参数传给 useErrorBoundary 回调，并更新对外及内部类型。 检查回调收到 error 和空信息对象；既有错误捕获路径有回归，类型声明无单独断言。

仓库 `preactjs/preact`，基线 `9457b221fdacd1052ffdb385918e1bab4b10e833`。参考改动：`hooks/src/index.d.ts`、`hooks/src/index.js`、`hooks/src/internal.d.ts`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。Node/npm 可用；Chrome 未预装，可达官方 deb，浏览器测试还需其系统库。

证据：[基线](../../target/xad/baseline/preactjs__preact-3689.json) · [镜像清单](../../target/xad/images/preactjs__preact-3689.json) · [对照记录](../../target/xad/checks/preactjs__preact-3689/controls.json) · [Workbench 导入](../../target/xad/imports/preactjs__preact-3689.json) · [宿主回放](../../target/xad/checks/preactjs__preact-3689/replay.json)

noop：FAIL_TO_PASS 0 通过 / 1 失败、PASS_TO_PASS 3 通过 / 0 失败。[官方报告](../../target/xad/checks/preactjs__preact-3689/noop/official.json) · [测试日志](../../target/xad/checks/preactjs__preact-3689/noop/logs/evaluation/df-5f4eed8b53044896a31f5dcbf3d7b824/delveforge/preactjs__preact-3689/test_output.txt)

oracle：FAIL_TO_PASS 1 通过 / 0 失败、PASS_TO_PASS 3 通过 / 0 失败。[官方报告](../../target/xad/checks/preactjs__preact-3689/oracle/official.json) · [测试日志](../../target/xad/checks/preactjs__preact-3689/oracle/logs/evaluation/df-5b614e7af43c4f418e6f07918e3bf2fb/delveforge/preactjs__preact-3689/test_output.txt)

### facebook__docusaurus-9183

代码围栏及 Prism 默认/额外语言名忽略大小写，涉及主题配置与 CodeBlock 渲染。 评分断言检查配置归一化，示例页面不能单独证明实际高亮效果。

仓库 `facebook/docusaurus`，基线 `b96004e77ebfb23e7349fa495d2177008186bd42`。参考改动：`packages/docusaurus-theme-classic/src/options.ts`、`packages/docusaurus-theme-classic/src/theme/CodeBlock/Content/String.tsx`、`website/docusaurus.config.js`。

**暂缓。** 静态已核；基线拒绝；只核官方清单/平台。模板 styles.module.css 被现有基线校验拒绝。 固定基线含模板符号链接（120000）。

证据：[基线](../../target/xad/baseline/facebook__docusaurus-9183.json) · [镜像清单](../../target/xad/images/facebook__docusaurus-9183.json) · [对照记录](../../target/xad/checks/facebook__docusaurus-9183/controls.json)

### nushell__nushell-13831

split column -n/--number 最多生成 n 列，剩余内容归入最后一列。 只验 -n 3 示例，未分别验证空值、极值等边界；无显式回归。

仓库 `nushell/nushell`，基线 `fb34a4fc6c9ca882cc6dc95d437902a45c402e9a`。参考改动：`crates/nu-command/src/strings/split/column.rs`。

**暂缓。** 对象核验＋导入＋空差异＋参考树回放；只核官方清单/平台。官方 Rust 1.79；当前无法冷取 Cargo 依赖。 crates 索引与包下载被实际开发代理拒绝；无仓库内 vendor。

证据：[基线](../../target/xad/baseline/nushell__nushell-13831.json) · [镜像清单](../../target/xad/images/nushell__nushell-13831.json) · [对照记录](../../target/xad/checks/nushell__nushell-13831/controls.json) · [Workbench 导入](../../target/xad/imports/nushell__nushell-13831.json) · [宿主回放](../../target/xad/checks/nushell__nushell-13831/replay.json)

### tokio-rs__tokio-6724

write_all_buf 在支持时选择 vectored write，普通 writer 继续使用原路径。 替身 writer 禁止普通写并核对输出；未测吞吐提升。

仓库 `tokio-rs/tokio`，基线 `0cbf1a5adae81e8ff86ca6d040aeee67cf888262`。参考改动：`tokio/src/io/util/write_all_buf.rs`。

**暂缓。** 对象核验＋导入＋空差异＋参考树回放；只核官方清单/平台。官方 Rust 1.85；当前无法冷取 Cargo 依赖。 crates 索引与包下载被实际开发代理拒绝；无仓库内 vendor。

证据：[基线](../../target/xad/baseline/tokio-rs__tokio-6724.json) · [镜像清单](../../target/xad/images/tokio-rs__tokio-6724.json) · [对照记录](../../target/xad/checks/tokio-rs__tokio-6724/controls.json) · [Workbench 导入](../../target/xad/imports/tokio-rs__tokio-6724.json) · [宿主回放](../../target/xad/checks/tokio-rs__tokio-6724/replay.json)

### javaparser__javaparser-4561

对文本块上的 String.formatted 调用解析方法；参考改动集中在符号求解器的类型提取。 目标检查 resolve 不抛异常，未逐项断言返回的签名。 题面示例使用 formatted，官方测试样例使用 format；两者共享文本块接收者的类型提取路径。

仓库 `javaparser/javaparser`，基线 `5be2e76b62e43b3c524b94463afe2294ac419fc8`。参考改动：`javaparser-symbol-solver-core/src/main/java/com/github/javaparser/symbolsolver/javaparsermodel/TypeExtractor.java`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。官方 JDK 17；开发环境 JDK 21/Maven。导入时 Git 发出长路径提示，随后对象和零差异检查通过。

证据：[基线](../../target/xad/baseline/javaparser__javaparser-4561.json) · [镜像清单](../../target/xad/images/javaparser__javaparser-4561.json) · [对照记录](../../target/xad/checks/javaparser__javaparser-4561/controls.json) · [Workbench 导入](../../target/xad/imports/javaparser__javaparser-4561.json) · [宿主回放](../../target/xad/checks/javaparser__javaparser-4561/replay.json)

noop：FAIL_TO_PASS 0 通过 / 1 失败、PASS_TO_PASS 0 通过 / 1 失败。[官方报告](../../target/xad/checks/javaparser__javaparser-4561/noop/official.json) · [测试日志](../../target/xad/checks/javaparser__javaparser-4561/noop/logs/evaluation/df-e0a09f645d9b4a7482044f1ba713989a/delveforge/javaparser__javaparser-4561/test_output.txt)

oracle：FAIL_TO_PASS 1 通过 / 0 失败、PASS_TO_PASS 1 通过 / 0 失败。[官方报告](../../target/xad/checks/javaparser__javaparser-4561/oracle/official.json) · [测试日志](../../target/xad/checks/javaparser__javaparser-4561/oracle/logs/evaluation/df-4c14e225f83248d785f5af6f2d9fe1a0/delveforge/javaparser__javaparser-4561/test_output.txt)

### prometheus__prometheus-11859

重启时处理超前于 WAL 的内存快照，避免过时状态影响恢复；核心修改在 TSDB Head。 复建 WAL 并保留旧快照，直接验证恢复路径；单文件变更仍有跨状态推理。

仓库 `prometheus/prometheus`，基线 `5ec1b4baaf01dae0b22567db53886c83b74e0e76`。参考改动：`tsdb/head.go`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。Go 1.23.8 和模块代理可达，涉及本地文件系统。

证据：[基线](../../target/xad/baseline/prometheus__prometheus-11859.json) · [镜像清单](../../target/xad/images/prometheus__prometheus-11859.json) · [对照记录](../../target/xad/checks/prometheus__prometheus-11859/controls.json) · [Workbench 导入](../../target/xad/imports/prometheus__prometheus-11859.json) · [宿主回放](../../target/xad/checks/prometheus__prometheus-11859/replay.json)

noop：FAIL_TO_PASS 0 通过 / 1 失败、PASS_TO_PASS 1 通过 / 0 失败。[官方报告](../../target/xad/checks/prometheus__prometheus-11859/noop/official.json) · [测试日志](../../target/xad/checks/prometheus__prometheus-11859/noop/logs/evaluation/df-cdc73f59260e4791b84ce4ef6fab4479/delveforge/prometheus__prometheus-11859/test_output.txt)

oracle：FAIL_TO_PASS 1 通过 / 0 失败、PASS_TO_PASS 1 通过 / 0 失败。[官方报告](../../target/xad/checks/prometheus__prometheus-11859/oracle/official.json) · [测试日志](../../target/xad/checks/prometheus__prometheus-11859/oracle/logs/evaluation/df-6fecc73535c24b488588e5edf318a3e0/delveforge/prometheus__prometheus-11859/test_output.txt)

### tokio-rs__tokio-4867

发送端关闭后重新订阅 broadcast receiver 应报告 Closed，涉及收发端共享状态。 新增目标断言 try_recv 返回 Closed，未直接用超时断言异步 recv；另有 22 项回归。

仓库 `tokio-rs/tokio`，基线 `53cf021b813c61cdeace26e863c89f65f6e92abd`。参考改动：`tokio/src/sync/broadcast.rs`。

**暂缓。** 对象核验＋导入＋空差异＋参考树回放；只核官方清单/平台。官方 Rust 1.85；当前无法冷取 Cargo 依赖。 crates 索引与包下载被实际开发代理拒绝；无仓库内 vendor。

证据：[基线](../../target/xad/baseline/tokio-rs__tokio-4867.json) · [镜像清单](../../target/xad/images/tokio-rs__tokio-4867.json) · [对照记录](../../target/xad/checks/tokio-rs__tokio-4867/controls.json) · [Workbench 导入](../../target/xad/imports/tokio-rs__tokio-4867.json) · [宿主回放](../../target/xad/checks/tokio-rs__tokio-4867/replay.json)

### caddyserver__caddy-5870

删除不存在的配置键须返回客户端错误；参考修复调整了配置访问的错误传播。 目标只检查 shouldErr，不直接检查 HTTP 层返回 4xx 或特定状态码；无显式回归。 基线测试工具只比较 err 是否为 nil；返回泛型错误也可能通过。仅作为较窄的配置错误处理样本。

仓库 `caddyserver/caddy`，基线 `fae195ac7eb9f4b0e9436384cd0529a11355e367`。参考改动：`admin.go`。

**准入。** 对象核验＋导入＋空差异＋参考树回放；本机镜像和 HEAD 已核。Go 1.23.8 和模块代理可达。

证据：[基线](../../target/xad/baseline/caddyserver__caddy-5870.json) · [镜像清单](../../target/xad/images/caddyserver__caddy-5870.json) · [对照记录](../../target/xad/checks/caddyserver__caddy-5870/controls.json) · [Workbench 导入](../../target/xad/imports/caddyserver__caddy-5870.json) · [宿主回放](../../target/xad/checks/caddyserver__caddy-5870/replay.json)

noop：FAIL_TO_PASS 0 通过 / 1 失败、PASS_TO_PASS 0 通过 / 0 失败。[官方报告](../../target/xad/checks/caddyserver__caddy-5870/noop/official.json) · [测试日志](../../target/xad/checks/caddyserver__caddy-5870/noop/logs/evaluation/df-ebd4ff18c72043adafd6a3bc8dc949d6/delveforge/caddyserver__caddy-5870/test_output.txt)

oracle：FAIL_TO_PASS 1 通过 / 0 失败、PASS_TO_PASS 0 通过 / 0 失败。[官方报告](../../target/xad/checks/caddyserver__caddy-5870/oracle/official.json) · [测试日志](../../target/xad/checks/caddyserver__caddy-5870/oracle/logs/evaluation/df-59f831be45b24b6fac01bf52aedb4700/delveforge/caddyserver__caddy-5870/test_output.txt)
