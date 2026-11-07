# ADR-0024：以原生命令替代活动语言适配工作流

- 状态：Accepted（2026-11-07，当前任务用户明确授权）
- 范围：现有单 Agent、Spring AI、Docker Workspace、Candidate/Delivery；不改变 Maven 模块、Aggregate 或数据库结构。
- 替代：ADR-0021/0022 的活动工具路由、安装/检查分阶段与语言准入；ADR-0020 的 PUBLIC_RESOURCE 模型不可见约定；ADR-0023 的活动 v5 生成与“必须修改测试”门槛。旧持久化证据仍按历史版本读取。

## 问题与决定

平台自行解释 Maven/npm/pnpm 的命令、修复分支和报告格式，使合法仓库因发现不确定或缺少解析器而无法执行。用户授权由仓库指引与原生工具决定构建语义，平台只承担执行边界、真实结果、源码身份及交付完整性。

活动模型工具固定为 `list_files`、`search_code`、`read_file`、`edit_file`、`exec_command`。精确编辑继续使用内部 Git 补丁预演及前后像检查；内部 apply_patch 不是模型工具。删除/重命名通过原生命令，最终差异仍独立重放。历史工具名称与证据 v1–v5 保持可读；旧容器/卷名称只保留在归属检查后的停止和清理路径。

`exec_command` 接受 command、相对 directory、单次 timeout_seconds；可附带 verification_scope 与 check_kind（test/build/other）。运行时绑定当前 Run，拒绝模型指定宿主挂载或安全参数。每次新进程、新容器，cwd/环境变量不跨调用保留；源码卷、项目依赖和该 Run 的缓存保留。命令通过 Base64 无损运输到容器后由 `/bin/sh` 解释，不在宿主解释。

### 2026-11-07 首次运行稳定性补充

`directory` 直接成为进程 cwd，命令内相对路径从该目录解析，不自动修正重复前缀。不存在的目录在启动前返回明确原因。安装是准备操作，可同时省略 verification_scope/check_kind；声明验证时两个字段须成对且有效。模型根据仓库 manifest/锁文件选择安装方式。npm cache 为 `/m2/npm-cache`，pnpm store 为 `/m2/pnpm-store`，实际依赖在项目自己的 node_modules。

`/bin/sh` 管道返回最后一个进程状态。过滤检查输出前先保存原命令状态，读取日志后返回原状态；不假设 pipefail 可用。退出码与可用报告中的失败数独立，退出 0 不能覆盖失败报告。平台不改写任意 shell 的语义，也不放宽完成门槛。

常规首次成功的 provider 诊断折叠到技术详情，失败与重试仍在主时间线；原持久化事件和用量保持完整。命令摘要区分未启动、目录/参数无效、非零退出、超时/取消与结果不可用，stdout/stderr 默认折叠并沿用净化和限长。模型主动返回 failed 仍记为 MODEL_INCOMPLETE，展示净化说明但不将说明视为独立证据或人工取消。

## 执行与资源边界

命令容器使用非 root、cap-drop ALL、no-new-privileges、只读根、内存/CPU/PID/临时目录限制。源码可写，正式 WorkingCopy、原资产、宿主凭据及 Docker socket 均不挂载。可信命令收据、Candidate 元数据和 Git 重放材料只在主机由平台写入。每次调用结束（包括超时）停止并移除整个命令容器，之后采集源码；冻结先停止全部写入者，再用全新只读捕获容器取最终状态。

PUBLIC_RESOURCE 经现有严格内容分类确认为公开后，允许执行与模型读取，通过真实只读绑定挂载保护原字节。任意 shell 可以输出它可读取的内容，输出净化不构成模型不可见边界。PRESERVED_ONLY 不物化，最终保留原 Git blob；缺失必要源码/构建输入不能产生可交付验证。普通源码凭据规则沿用 ADR-0020 补充，未放宽私钥/已知令牌/专用凭据分类。

项目网络只接入 Docker internal isolated bridge。可信代理另接外部网络，允许：

- HTTP 反向工件路径 `/maven2/` 和 `/npm/`，复用既有校验、有限诊断和原子缓存；
- HTTPS CONNECT 到 `repo.maven.apache.org`、`repo1.maven.org`、`registry.npmjs.org`、`registry.npmmirror.com`、`registry.npm.taobao.org`、`registry.yarnpkg.com` 的 443 端口；其余主机/端口拒绝。

代理保持 512 MiB 内存上限，完整 npm 元数据串行处理以避免并发 JSON 展开耗尽内存；tarball 仍可并行下载。原生 Maven 配置镜像到受控 Central；npm/pnpm 配置代理 registry，使用各自版本支持的环境配置前缀。pnpm store 位于独立 `/m2/pnpm-store`，避免 pnpm 12 在源码父目录生成缓存链接；Maven Wrapper 下载目录也位于 `/m2`。原锁文件 HTTPS 地址可通过固定主机隧道访问，保留原生完整性校验。项目生命周期脚本和 Maven 插件与其他命令具有相同的工作区写入和受控网络能力；不再声称“所有项目代码无网络”。代理不提供任意上游、宿主服务或凭据；需要其他服务/私有仓库时返回实际错误，不自动放开网络。CONNECT 流量不进入反向工件缓存；项目本地与 Run 缓存仍可复用。

## 环境保留与退役

保留 JDK 17/21 的镜像选择、固定 Node/npm/pnpm 和 pnpm 8 环境选择，支持已安装版本；没有系统 sudo 或任意工具链自动安装。有明确 shell shebang 的脚本中，独立空字符串初始化以及 `${NAME:+word}` 存在性分支不属于凭据赋值；非空赋值、拼接、默认值展开与已知秘密继续受保护，并明确识别 Shell 的 `:=` 默认赋值，不因 wrapper 文件名豁免。Maven 布局类仅用于可选 JDK 建议与既有 WorkingCopy 准备来源分类，不是活动 Agent 的准入、命令路由或完成条件。无法静态判定时使用显式/默认镜像，真实命令报告阻塞。

删除已失效的 dependency-retries 配置及专用检查环境变量、EnvironmentReadiness、Python Maven/front-end 执行/依赖修复路由、frontend.py、强制 Node/Vitest 报告注入和无调用者的 MavenFullSuiteInputs。保留共享 Git 编辑/差异、资源分类、工件代理、JDK 环境选择、历史 BaselineCheckComparison 与证据读取。`test_reports.py` 仅补充读取本次生成的 JUnit XML，不选择执行命令，不要求每个仓库提供该报告。

## 源码与验证

Git ignore 决定新未跟踪材料的候选范围，少量运行目录（node_modules、缓存、临时检查目录）排除；Git 已跟踪和已捕获/明确精确编辑的源码优先于 ignore。新增忽略规则不能隐藏这些文件的修改与删除。文件类型/大小、路径、凭据变更以及最终 Git 重放仍独立检查；不按 Java/JavaScript 扩展名决定源码成员。

证据 v6 记录实际 command/cwd、调用身份、scope/kind、退出码、超时/取消、有限输出及截断、命令前后源码身份。首次声明的必要命令范围保留，同命令重试不能撤销范围。发现命令可以不标记为验证；echo/true 等空操作不能声明为必要检查。命令语义由 Step 与仓库指引决定，平台不假装能证明任意命令等价于业务验收。

所有必要命令的最新结果必须退出 0、未取消/超时且前后源码身份均等于冻结源码。任何源码/配置/依赖声明变化保守地使旧结果过期；检查自身改源码时也须对稳定源码重跑。未执行、失败、缺失与过期结果在同一循环中反馈。命令成功、可用测试明细与业务验收分别表达；没有报告不阻塞合法通用检查，也不虚构测试数量。

可选报告不能覆盖退出失败；已观察到的失败用例必须后来实际通过，不能靠删除、跳过或不再生成报告清除。没有报告的工具链不具备通用逐测试语义证明。v5 历史债务仍按强可比性读取；v6 不自动将剩余失败解释为旧债务。通过的相关最终检查不因 POM/config identity 改变或没有修改测试文件而失败。

失败后的安全部分捕获写为 `partial.diff` 与 `partial-metadata.json`，标记 UNVERIFIED_NON_DELIVERABLE，不创建 Candidate，不触发 Delivery。捕获不安全则记录不可用，不能伪造产物。资源停止/归属未证实时不释放占用；不实现恢复运行。

## 恢复与呈现

已证明未写入的普通编辑拒绝、参数错误与命令失败返回同一循环；进展统一使用现有短重复循环检测，忽略命令日志中的时间变化。无累计 token、工具调用或总时长终止预算。

Spring AI HTTP 请求层最多三次尝试，仅重试传输错误及 408/429/500/502/503/504，单次退避/Retry-After 最多 2 秒；SDK retry 关闭叠加。取消中断退避，不重试认证/计费/无效请求。重试仅重发同一请求，不回放工具或追加重复历史；失败尝试用量未知时总用量保持未知。诊断只有分类、状态、尝试序号、时长和是否响应。

界面将当前源码的必要验证与逐调用历史分开，最近记录优先，保留相同显示名的不同尝试，明确过期、失败、未知明细与加载不完整。正常自动 Delivery、独立正式 Git 检查、逐 Step 授权及全部 Step 成功后才关闭 Plan 的规则不变。

## 取舍与验证边界

选择薄环境配置加通用命令，避免新增语言适配器、工作流引擎和策略框架。代价是需要模型依据仓库指引选择恰当命令，任意检查的业务意义不能由平台通用解析；工具链/允许网络之外的需求必须明确报告。确定性 Docker/Git/SQLite 回归证明机制，不证明真实模型的产品结果。实测结果与用户 E2E 步骤记录于 `docs/validation/generic-agent-command-path-20261107.md`；本次不发起付费模型或真实用户项目运行。
