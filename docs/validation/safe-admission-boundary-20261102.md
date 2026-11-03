# 安全仓库接入边界与真实仓库验证

基线：`353920aa6a16176bdc91c665bbc5ed96f41f6ab3`，实现提交：`2396d1a94614dacd1387ac06f4fbc76810c90f86`，分支 `codex/full-stack-evolution-checks`。
本轮实现已完成；**READY_FOR_REAL_PRODUCT_TRIAL = NO**。公开 Vue 样本完成实际构建，但公开 Maven 样本没有取得检查成功证据，不能把确定性交付夹具代作公开仓库或完整产品验收。

## 根因与选择

旧路径政策同时承担 Git 准入、模型披露、执行传输和修改授权，公开模板也可能阻塞整个仓库；单独放开预检，后续 Python 捕获或独立 Delivery 仍会拒绝。

[ADR-0020](../decisions/0020-repository-content-capabilities.md) 采用一个 Infrastructure 内容能力策略：

| 处置 | 原 Git | 模型内容 | 执行环境 | Agent 修改 |
| --- | --- | --- | --- | --- |
| SOURCE | 完整保留 | 既有净化后可见 | 可用 | 既有授权及行保护 |
| PUBLIC_RESOURCE | 完整保留 | 隐藏 | 原字节可用 | 禁止 |
| PRESERVED_ONLY | 完整保留 | 隐藏 | 不传入 | 禁止 |

公开配置采用受限正向语法，`.npmrc` 只接纳固定公共 registry 和明确的布尔设置；X.509 公有证书必须实际解析成功。文件名不是放行依据，私钥、实际凭据、未知敏感配置完整保留在可信主机。
普通源码沿用明确的凭据识别范围，不声称扫描可以证明所有未知内容无秘密。

正式 WorkingCopy 和可信 Git 保留完整 R。容器树 E 仅从独立 index 删除 PRESERVED_ONLY 条目，记录 `executionBaselineTree`；不修改原库、不创建替代领域 baseline，也不称为完整 checkout。
path/mode/SHA-256/size 能力清单由主机产生。快照包含实际材料和仅保留条目的身份；Candidate 从完整 R 重放，保留资源的 blob/mode 不得变化。Delivery 独立复核修改前后内容能力及原 R/D/B/C 守卫。

## 实现与安全验证

- Git 接入、Workspace 模型材料读取、两条 Analysis 读取链路、Java/Python 模型工具、执行传输、快照、冻结及独立 Delivery 使用同一能力边界。
- 模型 list/search/read/edit/patch 不能访问资源内容；分析跳过不可披露文件，不用虚构占位源码形成 Evidence。
- 未物化的源码、测试或构建描述使检查不可用，不能靠移除它们生成 PASS；已有失败仍须有效重验。
- 安装仍为固定 npm/pnpm、冻结锁、固定公共代理、禁用生命周期脚本、独立依赖准备和无网络检查；公开 `.npmrc` 不复制到联网安装目录。
- sourceView 记录 FULL/PROJECTED 及资源数量；检查与最终视图须一致，数量须合法。UI 显示实际范围。
- 负例覆盖：模板内真实口令/已知密钥/非空默认值、URL 凭据、私有 registry/auth/hooks、未知 npm 设置、私钥/无效证书、链接与保留目录、Git 属性转换、绕过工具物化秘密、只读资源修改、伪造资源 Candidate、失效/缺失源码证据。

没有新增依赖、模块、Aggregate、数据库结构、领域生命周期或自由 shell 工具。原始资产只读、逐 Step 授权、失败冻结与确定性 Delivery 语义保持。

## 本轮回归

条件跳过与显式 Docker 运行分别记录；以下均为本轮新运行。

| 检查 | 实际证据 |
| --- | --- |
| Maven Wrapper 全后端 verify | 1834 项，0 failure/0 error，41 conditional skip；实际执行 1793 项。模块：domain 209、application 860、infrastructure 543、app 222 |
| 纯 Maven / npm / pnpm 实际 Docker 交付 | 3/3 通过，0 skip；真实失败、修正、重验、冻结与 Git/SQLite 正式 Delivery |
| 更新的 Docker 隔离负例 | 4/4 通过，0 skip；其他原有 Docker 测试本轮较早运行 13 项通过、4 项旧合同不符，修正后四项独立重跑通过 |
| 最终镜像策略与进程测试 | 70/70 通过；另 7/7 实际镜像协议测试通过 |
| 最终应用打包 | verify 构建通过；包内 sandbox.py/frontend.py/proxy.py 与最终源码字节一致 |
| 前端 | 22 文件、305 测试通过；typecheck/build 通过 |
| UI 可见性 | 实际组件与当前 CSS 的验证夹具完成桌面及 390px 浏览器检查，无横向溢出；不代表完整产品验收 |

三条交付都包含公开 `.env.example`、公开 `.npmrc`、仅主机保留的合成凭据 `.env.local`；交付前正式副本未变，交付后资源原字节不变，原资产始终未变。三个 Candidate 的证据均为 PROJECTED、保留 1 项、公开只读 2 项，并记录 E 的树身份及当前测试结果。
三条 Candidate/交付 revision、E、检查及失败历史保存在结构化结果的 `deterministicDockerFixtures`，不是模型真实试验。

最终 v6 镜像由正常初始化流程固定，Node 22.16.0 / npm 10.9.2 / pnpm 12.9.1 未改变：

- JDK21：`sha256:022b19a745d8c91c1507eb886c57509187b0f5786c031e6d9bc288dff86fb5e4`。
- JDK17：`sha256:22f57d6b3af7191698a4f473bb22a6c428b2b8650b42a3bbb5d6e1dde4f0a9ba`。

最终 native/协议回归、三条交付和首批三个公开探针使用 JDK21 镜像。另对同一 Petclinic 固定版本补做一次推荐 JDK17 的探针，使用最终 JDK17 镜像；同样在依赖准备超时，没有完成实际 Maven 检查。原 JDK21 结果没有被覆盖。

首次完整回归发现 JS 模板例外误放行 Java 普通字符串 `${JWT_SECRET}`，原负例没有被改弱。例外已收窄到明确的 JS/Vue 反引号头部模板，原 48 项凭据测试恢复通过。普通引号、非空默认值、调用/拼接及已知密钥仍受保护。
三条 Docker 交付在该 Java 收紧前运行；其夹具不含这种源码形态，容器脚本未变化。最终完整回归覆盖收紧后的政策；公开样本的能力分类另行复核，不把旧运行描述成重跑。
13 个完整 Git 样本已在收紧后全部重新评估，状态和逐文件资源能力与收紧前一致；公开探针的材料范围仍适用。

较早的 Docker 回归保留 2 fail/2 error：敏感源码已不再复制供读取/修改，绕过工具的注入须检测重新物化，另有旧断言未跟随既有 PATCH_CONFLICT 合同。测试现检查更严格的拒绝、原资产完整性及未开始修改事实，失败日志均保留。
另有一次 native 测试挂载布局错误、一次受限宿主前端进程权限错误；更正运行环境后通过，未放宽产品边界。

## 公开仓库结果

[26 样本前后矩阵](safe-admission-matrix-20261102.md) 与 [固定版本结构化结果](safe-admission-results-20261102.json) 保留全部分母与拒绝。
25 个版本固定成功、1 个 PIN_FAILED；最终镜像重新评估已保存的固定元数据，24 个技术栈可识别，**不是 24 个可构建仓库**。
13 个样本执行完整 Git 检查：5 个接入成功、8 个明确拒绝；其余仅元数据，不推断接入成功。

| 未修改公开样本 | 本轮实际隔离结果 | 完整性 |
| --- | --- | --- |
| Homer，pnpm | 冻结安装及无网络构建成功：BUILD_PASSED_NOT_TESTED；没有 typecheck/test 脚本，零测试；PROJECTED 9 项，无被保留源码 | 原库与正式准备副本 232 文件不变，清理成功 |
| Spring Petclinic，Maven | Git/隔离准备成功；JDK21 与推荐 JDK17 两次 Maven 准备均在 540 秒超时，无实际检查结果；PROJECTED 7 项，无被保留源码 | 两次原库与正式准备副本 132 文件均不变，清理成功 |
| VueTorrent，npm 保留样本 | 原来 `.env.sample` 的整体接入拒绝已消除；两份含受保护字面量的源码仅主机保留，所有检查声明 UNAVAILABLE，调用 CHECK_NOT_AVAILABLE；没有执行安装/测试 | 原库与正式准备副本 422 文件不变，清理成功 |

Homer 与 Petclinic 首次 JDK21 尝试使用本轮较早 v6 镜像；最终 v6 只另改进被保留源码时的检查声明。VueTorrent 与 Petclinic 的 JDK17 补充尝试使用最终镜像。
共 3 个公开仓库、4 次隔离探针，没有外部修改、删除、改写 POM/锁文件或对目标源码打补丁，也没有切换新的仓库或调用模型。公开样本没有生成 Candidate/Delivery。

JDK17 补充观察由只读外部探针完成：有界 Maven 日志经现有凭据政策净化，Java 版本 Enforcer 通过，最后阶段为 `checkstyle:3.6.0:check (nohttp-checkstyle-validation)`。
537.93 秒时主线程等待 Aether `BasicRepositoryConnector` 下载，由 `DefaultPluginDependenciesResolver` 解析插件依赖；依赖目录文件数由 1818 增至 2025，代理未记录错误类别。
这将阻塞定位为 checkstyle 插件依赖准备超时，不是测试失败，也未证明具体网络/代理环节或投影导致超时；未读取堆、环境变量或向项目注入修复。

开发样本 Homer 暴露 Fetch 标准 credentials 枚举与 JS 模板的误判后做了通用修正；没有仓库、commit、示例/test/mock 路径豁免。
本政策保留样本是 VueTorrent、vue-ts-starter、sa-token：此前任务已经看过，不能称为完全未知样本；本轮没有按其结果调优内容政策。
Petclinic 超时后从原 26 个分母增加 1brc/sqlite-jdbc 两个 Maven 诊断样本，两者被原 Maven 边界拒绝，均保留。

剩余边界包括 Maven profile/多个根/JDK toolchain/module 数量、pnpm8/复杂 workspace、Yarn、旧 Vue/非 Vite/非 Vue、未支持脚本，以及 symlink/submodule。
链接安全支持需要链接身份、目标权限和跨平台捕获/重放规则，本轮继续明确拒绝，不静默解引用。
公开资源证书已识别，例如 jsoup 的公有证书，但其 Maven profile 仍拒绝；不把修复一个接入因素描述为构建成功。

## 就绪结论与最小后续

**READY_FOR_REAL_PRODUCT_TRIAL = NO；ASTRA_READY = NO。**
安全能力边界与确定性 Maven/npm/pnpm 交付已建立，但本轮只有公开 pnpm 构建成功，公开 Maven 依赖准备超时；公开 npm 保留样本只能证明安全拒绝。尚不足以证明声明的 Java/Maven 与 Vue/Vite 范围均可在未修改普通仓库上完成适用检查。

最小后续是在 `DockerAgentSandbox.prepareDependencies` 的 TIME_LIMIT 路径清理容器前，保留现有有界、净化的容器阶段日志；当前正常返回才读取日志，超时直接丢失诊断。
然后针对已观察的 checkstyle 插件依赖解析，核对公共代理的单工件响应时间与缓存命中，并只修复证实的原因，对同一固定 Petclinic/JDK17 重验。不放开网络、延长到无界、改写目标项目或持续切换仓库求成功。
无需继续扩展包管理器、框架或链接能力来获得展示资格。

本轮没有启动新的 DeepSeek/完整产品试验，新增付费调用及费用均为 0。不存在新的 Discovery、方向、Plan 完成或业务/视觉验收可供宣称。
只有门槛实际通过后，才按附件第 9 节建立本次数字费用上限并提出首个真实完整产品试验；本报告不预造产品方向或计划。

最终应用包 SHA-256：`1983eea760e81302f857a0cb2a50f3f7ab036fd97a0b20003121393182bb605a`。

原始日志、JUnit、三个确定性交付证明、公开探针及源码完整性记录位于 `target/admission-boundary-20261102/`。
历史 `general-compatibility-*` 与先前失败产品试验保留，未重标为本轮成功。未推送、合并、修改真实数据库或触碰用户 `frontend/design/`、`grilling/`。
