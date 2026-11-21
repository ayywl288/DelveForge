# 最后一轮 SWE-bench Multilingual 准入

本轮审查指定的最后六个候选。六题完成固定资产与镜像身份核对，四题通过基线、正式导入、零差异导出和主机参考回放。最终暂缓六题，新增准入为零。

工作区为 main，HEAD 为 `673582a4cb7a96c893955b05fdd115f41df72a1b`。本轮新增本报告与[Batch 2 候选清单](batch-2-candidates.json)。清单记录每题基线、镜像、七份官方资产哈希、完整 F2P/P2P 名单、对照 Run ID 和原始日志摘要。原有八题注册表和 Batch 1 清单保持原样。

沿用任务版本 `6e08cbcd763c869abd7aae4bcb9e1b3991180777`、评分器版本 `02e7a74ffd0b707aab73d203fe87bdc7c76afc8e` 和原评分客户端镜像 `sha256:2dbe31865ca6083e9823992bbf2e2f37b57baaf207abf18991e4ec09947fe7b0`。U 表示官方 Unresolved，R 表示官方 Resolved。两项均为免费对照。通过导入的项目经过 Git 对象与工作树核验、零字节导出；参考补丁仅在宿主副本回放。

| 候选 | 语言 | 参考文件 | F2P/P2P | 基线 / Workbench 导入 | No-op / Oracle | 决定 |
|---|---|---:|---:|---|---|---|
| `micropython__micropython-12158` | C | 8 | 1/18 | 阻断 / 未执行 | 未执行 / 未执行 | 暂缓 |
| `briannesbitt__carbon-2813` | PHP | 3 | 1/3 | 通过 / 通过 | U / R | 暂缓 |
| `rubocop__rubocop-13653` | Ruby | 3 | 4/105 | 通过 / 通过 | U / R | 暂缓 |
| `fluent__fluentd-3616` | Ruby | 1 | 1/2 | 通过 / 通过 | U / R | 暂缓 |
| `prometheus__prometheus-9248` | Go / TypeScript | 9 | 2/10 | 通过 / 通过 | U / R | 暂缓 |
| `apache__druid-13704` | Java | 2 | 1/2 | 阻断 / 未执行 | 未执行 / 未执行 | 暂缓 |

## 开发环境与资源

在实际导入的 Prometheus Project 启动工作台环境，并核对四个固定镜像、内部网络、代理策略以及容器限制。现场值为 2 CPU、3221225472 字节内存和 768 PID。Java 21.0.7、Maven 3.9.9、Node/npm/pnpm 与 Python 可用，gcc、make、CMake、Autoconf、PHP/Composer、Ruby/Bundler 和 Go 均未预装。
Ubuntu 索引实际获取 34.0 MB，模拟安装 PHP、Composer、Ruby 和 Bundler 成功。系统仓库有 php-psr-clock、PHPUnit 和 JDK 11；未查到 ruby-prism 候选。Fluentd 要求 parallel_tests ~> 0.15.3 及 webrick < 1.8.0，系统候选分别为 4.1.0 和 1.8.1。没有为单题改造依赖或手动拼装测试环境。[软件包记录](../../target/xad2/dev/apt-packages.log)
Packagist 的 psr/clock 元数据、Composer 下载站、两个 RubyGems 入口和 GitHub 均被代理拒绝（CONNECT 403）。Ubuntu、Go 1.23.8 下载、golang.org/x/tools 模块索引、npm 的 @lezer/generator 与 Maven Central 均实测返回 HTTP 200。Prometheus 使用 goyacc 和 Lezer 生成语法；这些依赖入口可达，工作台中完整再生成和构建没有实测。MicroPython 官方 Unix 构建还需 libffi-dev 和 pkg-config。Druid 官方镜像所用 JDK 11 与开发镜像的 JDK 21 有别；该仓库没有进入受限开发环境。

E 盘可用空间从 25.10 GiB 至 21.44 GiB。Docker 数据文件位于 E:/develop/Docker-images/DockerDesktopWSL/disk/docker_data.vhdx，文件长度从 52.07 至 55.57 GiB。取得 4 个新评分镜像，按 pull.log 下载完成事件与去重层摘要可确认 967,000,509 字节（0.90 GiB）的压缩层，协议、Git、包索引及其他流量未计入。卷剩余空间也会受其他进程影响。

## 最终数据集

建议下一轮仅运行下列八个已准入、尚未付费执行的 Batch 1 任务。连同已有六个 Pilot，形成 14 个已评或待评的任务，当前 Agent 成绩的分母仍为 6。Pilot 涵盖 JSON 树读取与适配器校验、Java AST 遍历、Go HTTP 上下文及头绑定、Vue 模板边界。Batch 1 扩展到 PromQL 函数与编辑器、持久化恢复、Vue 调度边界、Gson 旧 API、Caddy 配置及 HTTP 处理、Preact Hooks、JavaParser 符号解析。本轮没有增加经准入验证的 C、PHP 或 Ruby 能力。小样本经选择，每题一次有效 Trial 的内部解决率不能外推为排行榜 pass@1；免费参考补丁成绩不计入 Agent 成绩。

Pilot 六题沿用[原始评测结果](../RESULTS.md)，真实完成并获评六次，4 Resolved、2 Unresolved。Batch 1 的八道准入题见[原始准入矩阵](ADMISSION.md)，六道暂缓题、两个早期 Axios 准入结果及四道 Pro V2 的历史决定均保留。

建议下一阶段单次付费 Trial 的确切清单：

- `prometheus__prometheus-10720`
- `vuejs__core-11739`
- `google__gson-2024`
- `caddyserver__caddy-4774`
- `preactjs__preact-3689`
- `javaparser__javaparser-4561`
- `prometheus__prometheus-11859`
- `caddyserver__caddy-5870`

## 证据核验

正常工作区只引入两份 Batch 2 文档。独立工作目录为 `target/xad2/`；`checks/<ID>/<noop或oracle>/` 留存 client.log、invocation.json、official.json 与原始 test_output.txt。工作台探针及命令保存在 [dev.json](../../target/xad2/dev.json)。所有任务均从固定 Git 对象取官方材料，核对 linux/amd64 镜像身份。官方评分器在容器内再核对 /testbed 的 HEAD。

从仓库根目录只读复核本轮结果：

```powershell
python -B target/xad2/verify.py
git status --short --branch
```

下一步需另行批准冻结最终数据集及付费范围，按每题一个独立 Trial 准备 Project、工作目录、请求身份和证据目录。保留 OpenAI Responses、gpt-6.2-sol/high、96 请求、45 分钟、2 CPU、3072 MiB、768 PID 和并发 1。本次没有模型调用、付费云执行、正式 Agent Trial、提交或推送。

复核了 388 个既有文件的 SHA-256，并逐项将 Pilot 归档的 249 个条目与本地原件比对。隔离数据库中 Conversation、Run、model_attempt 各为 0。本轮启动的服务和五个已核对归属的容器已停止。Agent、Runner、官方评分器、网络策略及预算沿用原版本。

## 逐题审查

### micropython__micropython-12158

让 _thread.start_new_thread 返回新线程的整数标识。参考实现修改线程接口、调用方以及 Unix 和多个嵌入式移植。

官方脚本面向 Unix standard 构建。thread_ident1.py 比较返回值与线程内部 get_ident() 的类型和值，另列 18 个线程回归项。嵌入式移植没有对应的运行证据。

**暂缓。** 固定提交含 Git 子模块 lib/asf4（模式 160000）。现有基线流程在创建可导入副本前停止。Unix 构建、运行时线程行为及嵌入式目标均未执行。

仓库 `micropython/micropython`，基线 `c0d4c604e6a140c0f2967e1b43fd94d0b029c73f`；评分镜像 `sha256:a60fac34e98dec41c2382b07776ef7797cd78de5557cfa58a0c1efdbce404339`（linux/amd64）。

参考涉及 `ports/cc3200/mpthreadport.c`、`ports/esp32/mpthreadport.c`、`ports/renesas-ra/mpthreadport.c`、`ports/rp2/mpthreadport.c`、`ports/stm32/mpthreadport.c`、`ports/unix/mpthreadport.c`、`py/modthread.c`、`py/mpthread.h`。

证据：[基线](../../target/xad2/baseline/micropython__micropython-12158.json) · [镜像](../../target/xad2/images/micropython__micropython-12158.json) · [导入](../../target/xad2/checks/micropython__micropython-12158/import.json) · [对照](../../target/xad2/checks/micropython__micropython-12158/controls.json)。

### briannesbitt__carbon-2813

让 FactoryImmutable 实现 PSR ClockInterface，声明 psr/clock 依赖及其实现，并保持 now() 的不可变日期语义。

新增 PHPUnit 用例检查工厂接口、DateTimeImmutable 和 CarbonImmutable 返回类型；保留三个 Factory 回归项。该用例没有单独验证 Composer 虚拟包解析或完整日期 API。

**暂缓。** 干净项目需要 Composer 解析测试依赖。工作台缺少项目 vendor 目录，既有网络策略阻断 Packagist 和 GitHub，无法验证开发侧的标准安装与测试。

仓库 `briannesbitt/carbon`，基线 `8c019924327fe0251294adb61543981e3d27652c`；评分镜像 `sha256:a8cdcf7350ecbd31d2a37e9680c5882e9ba969db75b67ab138648087564572dc`（linux/amd64）。

参考涉及 `composer.json`、`phpdoc.php`、`src/Carbon/FactoryImmutable.php`。

证据：[基线](../../target/xad2/baseline/briannesbitt__carbon-2813.json) · [镜像](../../target/xad2/images/briannesbitt__carbon-2813.json) · [导入](../../target/xad2/checks/briannesbitt__carbon-2813/import.json) · [回放](../../target/xad2/checks/briannesbitt__carbon-2813/replay.json) · [对照](../../target/xad2/checks/briannesbitt__carbon-2813/controls.json) · [No-op](../../target/xad2/checks/briannesbitt__carbon-2813/noop/official.json) · [Oracle](../../target/xad2/checks/briannesbitt__carbon-2813/oracle/official.json)。

### rubocop__rubocop-13653

为 Style/AccessModifierDeclarations 增加 AllowModifiersOnAliasMethod。参考补丁包含规则、默认配置和更新说明。

四个新增目标分别覆盖 module_function、private、protected、public，另列 105 个回归项。测试显式配置开关；题面建议默认 false，官方参考配置默认 true，不能从显式配置的示例推断用户接受了该默认值。

**暂缓。** Gemfile 指向 RubyGems，并要求 prism ~> 1.2 等测试依赖。干净项目没有 vendored gems，工作台网络策略阻断软件源，无法验证标准 Bundler 安装和 RSpec。

仓库 `rubocop/rubocop`，基线 `34beb4adc7d00bf828031783d860441c1aed659a`；评分镜像 `sha256:5444b3a1fcd0eda794d1535fa0b3a6e152916b34d0297133db891f1e7cdb47b0`（linux/amd64）。

参考涉及 `changelog/change_update_style_access_modifier_declarations_to_add.md`、`config/default.yml`、`lib/rubocop/cop/style/access_modifier_declarations.rb`。

证据：[基线](../../target/xad2/baseline/rubocop__rubocop-13653.json) · [镜像](../../target/xad2/images/rubocop__rubocop-13653.json) · [导入](../../target/xad2/checks/rubocop__rubocop-13653/import.json) · [回放](../../target/xad2/checks/rubocop__rubocop-13653/replay.json) · [对照](../../target/xad2/checks/rubocop__rubocop-13653/controls.json) · [No-op](../../target/xad2/checks/rubocop__rubocop-13653/noop/official.json) · [Oracle](../../target/xad2/checks/rubocop__rubocop-13653/oracle/official.json)。

### fluent__fluentd-3616

HTTP input 插件接受 application/x-ndjson，并将每一行解析为单独事件。参考补丁仅改一个插件文件。

一个目标用两次 HTTP POST 覆盖 LF 和 CRLF，检查两次 HTTP 200 及四条事件的 tag、时间和内容。两个回归项覆盖 JSON 和 MessagePack。没有畸形输入、负载或跨模块验证。

**暂缓。** 标准 Bundler 安装依赖 RubyGems，项目未自带所需 gems，依赖包括 msgpack、yajl-ruby 等原生扩展。开发环境的软件源限制阻断了可复现的项目测试。

仓库 `fluent/fluentd`，基线 `26c62cddbf236361b797eac498301c7d4a77d010`；评分镜像 `sha256:6c68f9c237000946a3ccaf1b62588d7e0850ac9546b95fab2acfc64f2545a34e`（linux/amd64）。

参考涉及 `lib/fluent/plugin/in_http.rb`。

证据：[基线](../../target/xad2/baseline/fluent__fluentd-3616.json) · [镜像](../../target/xad2/images/fluent__fluentd-3616.json) · [导入](../../target/xad2/checks/fluent__fluentd-3616/import.json) · [回放](../../target/xad2/checks/fluent__fluentd-3616/replay.json) · [对照](../../target/xad2/checks/fluent__fluentd-3616/controls.json) · [No-op](../../target/xad2/checks/fluent__fluentd-3616/noop/official.json) · [Oracle](../../target/xad2/checks/fluent__fluentd-3616/oracle/official.json)。

### prometheus__prometheus-9248

题面要求两个标量之间使用 atan2。参考修改涉及查询运算、词法与语法、编辑器以及文档。9 个文件中，生成的 Go 解析器占参考补丁 1,038 行增删中的 1,008 行。

官方 F2P 为 TestEvaluations 及其 operators.test 子项，不能当作两个独立功能。新增用例的输入为向量，含 NaN；十个 P2P 为其他已有测试文件。与 10720 都涉及 PromQL 和编辑器，新价值主要在二元运算优先级及向量语义。

**暂缓。** 官方 Oracle 为 Resolved。在同一固定镜像、相同基线和参考补丁下另行离线运行：1 + 2 得到 3；题面原例 1 atan2 2 报 operator "atan2" not allowed for Scalar operations。参考实现仅为 vectorElemBinop 加入计算分支。按官方分数评估该题会漏记题面中的核心行为。[独立标量验证原始记录](../../target/xad2/probes/scalar.log)

仓库 `prometheus/prometheus`，基线 `1fb3c1b5980d2ae3f844b6b9ae2ad38117710aa0`；评分镜像 `sha256:5fd4537e3b4c4a3d6d55e6735a26b68d271ff0253b1d1932025859018c62a2cc`（linux/amd64）。

参考涉及 `docs/querying/operators.md`、`promql/engine.go`、`promql/parser/generated_parser.y`、`promql/parser/generated_parser.y.go`、`promql/parser/lex.go`、`web/ui/module/codemirror-promql/src/complete/promql.terms.ts`、`web/ui/module/codemirror-promql/src/grammar/promql.grammar`、`web/ui/module/codemirror-promql/src/grammar/tokens.js`、`web/ui/module/codemirror-promql/src/promql.ts`。

证据：[基线](../../target/xad2/baseline/prometheus__prometheus-9248.json) · [镜像](../../target/xad2/images/prometheus__prometheus-9248.json) · [导入](../../target/xad2/checks/prometheus__prometheus-9248/import.json) · [回放](../../target/xad2/checks/prometheus__prometheus-9248/replay.json) · [对照](../../target/xad2/checks/prometheus__prometheus-9248/controls.json) · [No-op](../../target/xad2/checks/prometheus__prometheus-9248/noop/official.json) · [Oracle](../../target/xad2/checks/prometheus__prometheus-9248/oracle/official.json)。

### apache__druid-13704

在算术后聚合器中加入 pow，并更新文档。参考补丁两处文件合计 11 行增删，构建仓库的规模不代表这项功能复杂。

目标 testPow 检查 4 的平方根，以及零、NaN 等底数的零次幂；两个回归项为除法和商。官方镜像用 JDK 11，预热 processing 及前置模块时把资源包的 SNAPSHOT 版本改为发行版。

**暂缓。** 固定提交含符号链接 publications/whitepaper/modii658-yang.bib（模式 120000）。基线流程先行阻断。现有 3072 MiB 环境未实测该仓库的 Maven 编译耗时或内存峰值，无法据此判定 OOM。

仓库 `apache/druid`，基线 `51dfde02840017092486fb75be2b16566aff6a19`；评分镜像 `sha256:eefba445fb52e9975889fce04ae88b6f14ae45053b68af3bdf816e3d4a0da785`（linux/amd64）。

参考涉及 `docs/querying/post-aggregations.md`、`processing/src/main/java/org/apache/druid/query/aggregation/post/ArithmeticPostAggregator.java`。

证据：[基线](../../target/xad2/baseline/apache__druid-13704.json) · [镜像](../../target/xad2/images/apache__druid-13704.json) · [导入](../../target/xad2/checks/apache__druid-13704/import.json) · [对照](../../target/xad2/checks/apache__druid-13704/controls.json)。
