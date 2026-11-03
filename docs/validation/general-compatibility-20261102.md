# 通用仓库兼容性审查与真实产品试验门槛

日期：2026-11-02。分支：`codex/full-stack-evolution-checks`。

## 决定

**工程修正已实现；广泛仓库兼容目标尚未达到。ASTRA_READY = NO。**

本轮没有启动新的真实 DeepSeek 完整产品试验。原因是附件要求先完成通用兼容性目标：
静态审查已发现相当比例的普通样本仍需外部适配，不能挑选一个可构建仓库代替这一目标。
按附件第 4、10 节，在合理任务范围内记录架构限制并停止，不为展示仓库增加例外或临时放开凭据边界。
用户已明确授权没有费用上限；停止原因不是预算，也没有再次索取授权。

保留 `0a2c7b0`、`17038fe` 及[上一轮失败记录](frontend-practical-compatibility-20261102.md)。
上一轮是指定小功能的 IT-Tools 试验，不追认为本轮完整产品试验或成功案例。
本轮没有代写画像、方向、Plan、目标应用代码或业务数据库状态，没有形成新 Candidate/Delivery 的真实模型事实。

## 样本与方法

[预声明名单](general-compatibility-corpus-20261102.json)包含 26 个不同维护者的公开仓库及固定版本。
先声明 24 个，再在评估前补充两个现代 Maven/前端混合仓库，以覆盖原名单缺少的类别；没有删去失败样本。
5 个预声明 holdout 中 1 个无法取得固定版本，仍在分母内。实际项目类型以读到的元数据为准，
例如 VueTorrent 实际采用 npm，SPlayer 已迁移维护者，Alibaba 管理端含多包结构，不能按抽样时的猜测分类成通过。

广泛审查只读取公开固定版本 tree、POM、manifest、锁和配置元数据，不运行仓库代码。
Git tree 元数据检查覆盖 24 个样本，其余保留获取失败或仅根目录检查的边界。
前端元数据验证使用产品同一 Python 解析器；审查投影只含实际下载的元数据，不是准备好的仓库。
未下载的文件标为未检查，不能据不完整投影推断仓库缺失文件。

各阶段分开：技术栈识别不等于隔离准入；隔离准入不等于依赖安装；构建不等于测试；测试不等于功能或视觉验收。
完整结果见 [逐仓库矩阵](general-compatibility-results-20261102.json)。所有 NOT_RUN、拒绝和取样失败均保留。

| 样本类别 | 分母 | 仅技术栈识别 | 静态已见阻塞 | 实际完整 Git 预检通过 / 已检查 |
| --- | ---: | ---: | ---: | ---: |
| Maven / Spring / 混合工程 | 14 | 13/14（92.9%） | 4/14 | 1/2 |
| 前端工程及预声明边界样本 | 12 | 11/12（91.7%） | 8/12 | 2/3 |

上表百分比是识别率，**不是兼容通过率**。完整 Git 预检只选 5 个代表样本；其余不估算成功。
至少 12/26 样本已经触发文件路径或 Git 条目限制；其中并非全是真实凭据风险。
这一结果不足以声称多数 GitHub 工程可直接使用 DelveForge。

下表另列实际执行阶段，分母是该阶段**实际尝试**的样本，样本总数仍全部保留。
`—` 表示未执行，不能解释为通过；5 个完整 Git 预检样本不是总体随机成功率。

| 实际类别 | 样本总数 | Git 预检通过 / 检查 | 依赖准备通过 / 尝试 | 已执行检查的项目 | 真实模型 Candidate/Delivery |
| --- | ---: | ---: | ---: | ---: | --- |
| Maven 库 / 应用 / native 库 | 4 | 0/1 | — | 0 | 未执行 |
| Spring 后端 / 服务端 UI / 嵌套入口 | 4 | 1/1 | 0/1 | 0 | 未执行 |
| Maven + 前端 / 示例集合 | 6 | — | — | 0 | 未执行 |
| 独立 Vue/Vite（含 Yarn/旧 pnpm 边界） | 9 | 2/3 | 1/1 | 1 | 未执行 |
| Desktop / Nuxt / Vue2 边界 | 3 | — | — | 0 | 未执行 |

Homer：根目录 pnpm、Vue JavaScript、root-only pnpm 配置，真实离线 build 成功，分类为 BUILD_PASSED_NOT_TESTED。
其 typecheck/test 均明确不可用，没有新增测试或宣称测试通过。232 个原始文件与准备副本字节保持不变，探针资源清理成功。
Spring Petclinic：唯一 Maven 入口被识别、WorkingCopy 与 Agent 隔离准备成功，但后续依赖准备容器阶段超时，
工具未返回检查结果，不能记 Maven 测试成功，也没有足够证据把超时归因于某个依赖或产品缺陷。
132 个原始文件与准备副本字节保持不变，清理成功。
IT-Tools 仅做本轮完整 Git 预检与元数据对照；jsoup 因测试证书/profile 拒绝；VueTorrent 最终仅剩 `.env.sample` 路径阻塞。

## 已证实原因与通用修正

| 原因 | 复现 / 改动 | 验证边界 |
| --- | --- | --- |
| 原工具默认 frontend，而 IT-Tools 工程在根目录 | 唯一 Vue/Vite 工程自动发现；多工程仍须明确目录 | 错误目录返回 CHECK_NOT_AVAILABLE，并给出可用目录，不伪造成功 |
| 模型不知道有没有 Maven / 可选前端检查 | 在既有 Port 返回有界只读声明，执行开始前传给模型；错误调用附声明 | 声明不是依赖或检查 PASS，也不是额外 Step 授权 |
| 测试资源的 POM 被误列为 Maven 工程 | 声明复用受控 Maven 入口和 reactor 角色 | 修改前后镜像对照：仅含测试 POM 时由 1 个 Maven 工程改为 0，增加确定性回归 |
| 精确脚本字符串排除常规组合 | 识别官方 Vue 包装、单层 Vitest 转调、受控 vue-tsc 参数、mode 和有界 Vite heap；转换为固定 argv | 不执行 run-p、任意 shell、Node 脚本或自由环境赋值 |
| 旧锁的合法 SHA-1 被 SHA-512-only 规则拒绝 | 支持规范 SHA-1/256/384/512 SRI，真实安装器继续校验 | npm/pnpm 的正确 SHA-1 能安装，错误摘要被拒绝；未禁用 integrity |
| TypeScript 和可选测试被当作前端必需条件 | Vue/Vite JavaScript 工程可构建；无 typecheck/test 返回不可用 | Homer 实际构建成功，零测试明确为 NOT_TESTED |
| root-only pnpm YAML 被一概当成 monorepo | 识别仅根包及安装构建许可字段；不把许可传给安装器 | ignore-scripts 始终优先，复杂 workspace/settings 继续明确拒绝 |
| npm11 元数据虽采用相同 v3 锁却被拒绝 | npm9–11 元数据族共享固定 npm10.9.2 安装器 | 锁格式兼容不是承诺支持全部 npm11 项目或其 Node engines |

IT-Tools 保留源卷上的只读对照已确认：旧实现根目录被 SHA-512-only 拒绝；新实现可以识别 typecheck、build、test，
包括现有包装脚本。该对照是元数据复现，**没有重跑该失败 Run 或证明其业务成功**。
原 `UNRESOLVED_CHECK_FAILURE` 是冻结守卫拒绝未解决结果，不能通过清除失败、伪造 PASS 或用无关 Maven 成功修复。
本轮保留该守卫，并回归真实失败只有有效重验才能解除。

历史 SRI 的依据见 [npm 锁文件文档](https://docs.npmjs.com/cli/v10/configuring-npm/package-lock-json/)
与 [npm ssri](https://github.com/npm/ssri)。SHA-1 不提供现代抗碰撞或发布者真实性保证；这是兼容旧锁的明确取舍。
包仍是不可信代码：固定 HTTPS registry、冻结安装、关闭生命周期、隔离网络、只读依赖、源码 SHA-256 快照及清理均保留。
设计取舍记录于 [ADR-0012](../decisions/0012-autonomous-agent-sandbox-and-frozen-candidate.md)。

## 实际回归与版本边界

| 检查 | 本轮实际结果 |
| --- | --- |
| Maven Wrapper 全后端 verify | 1820 项，0 failure、0 error、40 conditional skip；实际执行 1780 项 |
| 模块数量 | domain 209、application 858、infrastructure 531、app 222 |
| 显式启用 Docker 的完整交付回归 | 纯 Maven / npm / pnpm 共 3 项通过，0 skip；真实检查、冻结及 Git/SQLite Delivery |
| 最终镜像策略 / 代理 | 50 + 4 项通过；包括实际不可信进程修改源码的拒绝、失败分类及 optional checks |
| 最终镜像协议 | 7 项通过 |
| 最终应用打包 | Maven package 通过；应用内 frontend.py / sandbox.py / proxy.py 与最终源字节一致 |
| 原资产完整性 | Homer 232 文件、Petclinic 132 文件均保持原字节；准备副本也保持原字节，私有资源清理成功 |

3 个 Docker 交付场景的模型动作是确定性脚本，**不是真实 DeepSeek 产品演化**。
纯 Maven 场景没有前端工程或前端证据；两种包管理器实际经历 manifest/锁不一致、错误 SHA-1 摘要、
失败测试、类型或构建失败，修正后取得当前源码结果再冻结与正式交付。
其保留 JUnit 与三份交付结果位于 `target/compatibility-cycle-20261102/controlled-integration-junit.xml`
及 `fixture-delivery-{maven,npm,pnpm}.json`，不能以默认 verify 的条件跳过代替。

上述 Docker 交付与 Homer 构建使用较早的 v5 镜像
`sha256:3870ca9ae709728e51c669d66adf5be0e8a71d710192b8180760a22b60dcecbd`。
此后仅增加 npm11 元数据族及修正 Maven 声明的测试 POM 角色；没有把这些补充描述成重跑三种安装/交付。
这些场景采用 npm10 / pnpm12，且只有真实 Maven 入口，未涉及后来修正的输入。
最终资源另行完成 54 + 7 项测试，及测试 POM 的修改前后真实镜像对照。完整后端 verify 已通过；
最后 Python 声明修正由最终镜像测试单独覆盖，随后重新打包资源。

最终协议 v5 镜像已由正常初始化流程构建、核对脚本及固定 Node 22.16.0 / npm 10.9.2 / pnpm 12.9.1：

- JDK21：`sha256:b8ae218392ca20ae9be4f9369098fbe28f86948dd4b27e73ab8d89f425ed99b1`。
- JDK17：`sha256:3a3fa701df42a1f6e5102c18cd5fedd52db4c497d2f9e1c795a3a796786d8d93`。

54 + 7 项最终镜像回归运行于 JDK21；JDK17 完成启动器协议、脚本与固定版本核对。
最终应用包 SHA-256：`375c764ed6e69daada1bd3346d780cf71e0047d809329608287083ca9d08ed7e`。

本轮没有修改前端 UI，不把之前前端测试记录冒充新运行或视觉验收。没有新增依赖、Maven 模块或数据库结构。
首次完整回归在受限宿主失败：本地 Git clone 复现 Win32 error 5、无法创建 signal pipe；
同一最小诊断 clone 在授权环境成功，随后同一完整 verify 在该环境通过，未修改产品逻辑或削弱断言。
另一次策略测试容器的临时目录不可执行，导致两个进程测试启动失败；修正临时目录挂载后实际进程测试通过。
这些尝试的日志保留，不能把它们称为产品回归通过。

## Holdout

sa-token、VueTorrent、vue-ts-starter、vuestic-admin 在通用修改后才读取元数据；spring-petclinic-rest 固定版本获取失败。
VueTorrent 的第一次检查发现 npm11 人为限制，随后修正并加入回归；因此它不能再算完全未调优的最终 holdout。
其他样本没有修改：sa-token 只证明 Maven 元数据识别；vue-ts-starter 被 pnpm8/项目配置边界限制；
vuestic-admin 使用 Yarn4，超出 npm/pnpm 范围。没有把这些静态检查描述成安装或 Delivery 成功。

## 仍然存在的架构限制

**Current Problem：** 常见 `.env.sample`、`.env.example`、`.npmrc`、公开测试证书也会阻塞整个源码快照。
静态命名规则不能证明它们是秘密，但当前实现没有一个安全、明确、可交付的细粒度接纳路径。
jsoup 的测试证书与 Maven profile、VueTorrent 的 `.env.sample` 是实际完整 Git 预检例子。

**Existing Constraint：** 原资产只读；Agent 权威快照与 Candidate/Delivery 必须保持完整性，不能悄悄丢弃或改写文件。
凭据及禁止路径不能直接交给不可信项目代码或模型。Maven 当前还要求无歧义 reactor/JDK、受控 Central 来源及 17/21 镜像。

**Proposed Change（未实施的后续设计）：** 为公开非敏感配置、秘密材料、构建必需配置定义一致的导入/能力边界，
必要时增加显式用户决定及可验证的快照投影。相应决策应同时覆盖 Workspace、源码传输、检查容器、凭据策略及 Delivery 重放。
这不是本轮已建立的新领域规则，也不是“文件名安全即可放行”的结论。

**Why Existing Design Is Insufficient：** 只在预检放行，后续 Python 路径与快照仍会拒绝；只在构建中删除文件会破坏源码或改变构建语义；
直接复制所有配置可能暴露真实凭据或带入未知 registry/hooks。根本问题是准入、传输与可读/可执行能力目前共用较粗边界。

**Alternatives / Trade-offs：** 保留现有限制会牺牲普通仓库覆盖；按文件名开例外不可靠；按某个展示仓库修补被用户禁止。
安全的配置语义分类/投影需要独立设计和端到端完整性验证，超出本轮有限兼容性修正，不仓促引入。
未知 pnpm 安全设置、pnpm8 锁、多包 workspace、Yarn、Nuxt/Electron/native 安装构建及多 Maven 根/profile 也不能宣称已支持。

## 真实产品试验与就绪结论

新的完整产品试验次数：**0/2**；新的真实 DeepSeek 调用：**0**，本轮新增模型费用：**0**。
没有新的真实画像、方向、Plan、Step Run、Candidate、Delivery、应用截图或浏览器验收可供展示。
这些阶段为 NOT_STARTED，不能用确定性 fixture 的交付或原样 Homer 构建结果补位。

工程适配器的具体修正与回归结果单独记录；广泛仓库接入仍未就绪。完整产品与展示就绪均未证实，
**ASTRA_READY = NO**。后续应先解决上述导入/能力边界，而不是继续切换仓库寻找成功案例。

运行原始材料位于 `target/compatibility-cycle-20261102/`；公开仓库原样保留，独立受控探针会校验原资产与正式准备副本字节不变并清理其私有资源。
没有推送、合并或修改真实数据库。未改动用户的 `frontend/design/` 与 `grilling/`。
