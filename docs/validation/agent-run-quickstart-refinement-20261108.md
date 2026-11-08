# Agent Run 与 Quick Start 细化交接报告

日期：2026-11-08。范围：平台启动材料收集及界面呈现；不改 Agent Loop、v8 完成证据、沙箱、授权、Candidate 或 Delivery 事务。

## 1. 分支与交付

- 本地分支：`codex/agent-run-quickstart-refinement`。
- 实际基线：`fb61365432273bbcdfb10171d9efd9333ba7091e`，开始时为 main，较 origin/main ahead 17 / behind 0，tracked clean。
- 最终本地提交：包含本报告的提交，完整哈希见本次交接回复或 `git log -1 --format=fuller`。未合并、未推送。
- `codex-migration/`、`frontend/design/`、`grilling/` 保留。没有修改真实 Bookstore、原 Run `08b82f99-d14c-40a1-82e7-02f00e81156a` 的交付或留存材料。

## 2. 原因与材料收集

原实现同时受有限的文件名筛选和 `readModelFile()` 整文件安全拒绝约束。中文开发指南、嵌套 Wrapper 元数据等未进入候选；含凭据示例的 README、Compose、环境示例可能整体被排除。因此模型缺少后端及基础服务的启动依据。该原因来自代码路径和确定性回归；本次未重新调用真实 Bookstore 的模型请求。

新增 Application 内的 `QuickStartSources`，仍通过现有 Workspace 只读能力读取最终 Delivery **固定 commit**。不引入 Scout、RepositoryUnderstanding、模型审查器、执行容器或新 Workspace 适配器。仍只有一次独立 AiGateway 调用。

材料包括根 README、其本地文档链接、中英文安装/开发/部署指南、模块 README、Maven/Gradle Wrapper 与元数据、常见构建清单、启动脚本、Docker/Compose、应用配置和 `.env.example`。根 README 优先，再考虑文档、浅层模块文件，锁文件较后；不递归跟随链接，也不联网抓取内容。

明确限额：目录深度 8；最多读取尝试 64 个文件、接受 32 个文件；树元数据限制单文件 64 KiB，并检查解码文本长度；每份净化摘录最多 12K 字符，总材料连来源路径最多 48K 字符。超长摘录按完整行截断并加标记。跳过依赖和构建产物目录、二进制/解码异常材料。

## 3. 安全行为

- 先执行既有路径排除；实际 `.env`、私钥路径等不读。唯一窄例外是 `.env.example`：只保留合法变量名，所有取值和注释丢弃，统一写成 `<自行配置>`。
- 专用路径通过 `readFile()` 读取可信原文，在进入模型前完整处理；不改变通用 `readModelFile()`、`RepositoryContentPolicy` 或 `RepositorySecretPolicy`。
- XML 凭据元素先移除其值，再复用 `sanitizeSource` 和 `sanitizeOutput`。可安全净化的文档保留其余说明；Compose 可保留服务、端口等非敏感信息。
- 私钥块、敏感 YAML 多行块/无法闭合的凭据值、受识别的命令行口令形式整文件跳过；已有安全策略拒绝的材料也跳过。完整净化先于截断，原文不进入日志、持久化或模型请求。
- 模型系统提示明确将仓库材料视为不可信数据；来源路径及最终 revision 由平台附加，不由模型编造。

真实临时 Git 仓库回归检验了 README、指南、环境示例、Compose、XML 等样例的已知秘密值不进入 AiRequest，并证明原有 `readModelFile()` 仍拒绝敏感文件。这里使用的是既有模式识别加局部保护，不是任意格式秘密检测的证明；无法识别的自由文本仍是该策略的边界。

## 4. 项目名称与指南质量

标题优先取净化后的根 README 一级标题；无可靠标题时回退到原 Software Asset 的仓库目录名，避免使用 Working Copy 随机目录。模型输入没有采用 Plan 的功能名称改名。持久化查询只增加读取已有 Asset location，不增加表或迁移。

提示要求用 manifest/项目元数据交叉核对名称，分清仓库明载与从实际 Wrapper、manifest、脚本推导的命令；不编造端口、凭据、变量、URL 或启动顺序。正文限制 6000 字符，标题/来源附加后限制 12000 字符。生成结构为前置条件、必要配置、安装依赖、基础服务、应用启动、访问地址；未知事项汇总为一条说明。

固定 revision、完成 Plan 前提、授权/交付变化的再次校验、独立失败、显式重试及 stale 标识均保留。模型生成格式与事实质量受提示约束，未增加第二次调用或自然语言事实验证器。确定性测试证明材料与提示正确，不等同于真实模型必然给出完整准确指南。

## 5. Quick Start 界面

项目名与 Quick Start 标题分层，版本信息紧凑显示；前置条件短列表以紧凑标签呈现，正文保留编号章节和原复制按钮。访问链接要求展示实际 URL。统一只显示一条未执行启动验证说明。

真实来源路径、完整 revision 和内部标识放入默认收起的「生成依据」。既有旧格式指南仍可显示；没有自动重生成旧指南。Markdown 继续使用 Vue 文本节点与受限链接，不渲染任意 HTML、图片或脚本。

## 6. Run 普通视图与开发者详情

- 当前工作清单为时间线之外的独立浅色卡片，含完成数与状态；只展示最新一份，不重复清单事件。
- 普通时间线保留有意义的 Agent/平台进展、失败与阻塞，隐藏原始工具调用、Shell、输出和诊断。没有通过解析 Shell 文本虚构业务活动。
- 开发者详情默认收起，包含 Run 标识、时间、调用/token 统计、预检详情、命令/修改/探索记录、诊断与原始事件。原始记录和后端接口没有删除。
- 完整日志使用「查看完整日志／加载更多」，stdout/stderr 用选择控件切换；分页追加，不重复命令文本。保留退出状态、耗时、取消/超时、源码变化及截断/保留上限提示，去掉多层内部滚动容器。
- 成功预检只显示「运行环境已准备」；失败理由保持可见，详细探测归入开发者详情。
- 删除重复常驻灰色说明；保留启动费用确认、停止操作及相关状态。
- 最终结果按实现摘要、平台验证事实、剩余限制排列；完整 Agent 报告及历史明细默认收起。最终源码事实依据既有 snapshot/afterSnapshot 身份区分；历史执行的计数明确为累计且可能重复，不解释为唯一当前失败用例。未知测试明细不显示为通过 E2E。

## 7. JDK 与目录设置

检查了 API 和后端使用：`checkDirectories` 有实际的多 Maven 根筛选用途，影响 JDK 选择而非探索范围。因此保留兼容字段，界面移入高级设置并改名「Maven 工程根目录覆盖（可选）」，明确不改变探索或修改授权。

JDK 自动选择仍为默认；手动 17/21 同样移入高级设置，明确为目标项目 Agent 沙箱构建 JDK。已知非 Java 项目隐藏；缺少可判定元数据或后端要求选择时保留入口。未改变后端兼容校验，也未承诺支持其他 Java 版本。

## 8. 实际验证

| 验证 | 实际结果 |
| --- | --- |
| 后端定向测试与四模块 verify | 51 项通过，0 失败、0 错误、0 跳过；独立构建目录生成可执行 JAR |
| 前端完整测试 | 23 文件、338 项通过 |
| 最后日志分页断言更新后的定向回归 | 3 文件、149 项通过 |
| 前端 typecheck | 通过 |
| 前端 production build | 通过，101 modules |
| 浏览器桌面/窄屏 | 1440px 与 390px 共 10 张截图；无页面横向溢出，关键折叠/清单/命令不重复断言通过 |
| 变更范围与空白检查 | `git diff --check` 通过；没有新增依赖、数据库迁移或镜像修改 |
| 付费模型调用 | **NOT RUN** |
| 真实产品 AgentRun | **NOT RUN** |
| Bookstore 启动/业务 E2E | **NOT RUN** |

后端所跑测试为 QuickStartServiceTest（5）、QuickStartSourcesTest（4）、SqliteQuickStartStoreTest（1）、RepositoryContentPolicyTest（12）、DeterministicRepositorySecretPolicyTest（27）、AgentAcceptanceContextApiTest（2）。包含真实临时 Git 仓库、真实 SQLite、确定性模型替身；修改 HEAD/工作区后仍读取指定交付 commit。没有运行整个后端套件或 Docker 集成；本任务未改 Shell、沙箱和交付机制。

原目录 verify 的测试通过，但 repackage 因运行中的 Java 进程占用默认 JAR 而失败；重试仍失败。没有停止该应用。随后把本次 Maven 源码复制到忽略目录 `target/ux-refinement-build`，运行相同定向 verify，全部模块和可执行打包通过。此结果是源码构建验证，**不是当前运行服务已经更新**。默认输出目录经过失败打包，重启前应重新构建，不直接复用那份默认 JAR。

主要命令（仓库根目录 PowerShell）：

```powershell
.\mvnw.cmd -f target/ux-refinement-build/pom.xml verify '-Dtest=QuickStartServiceTest,QuickStartSourcesTest,SqliteQuickStartStoreTest,RepositoryContentPolicyTest,DeterministicRepositorySecretPolicyTest,AgentAcceptanceContextApiTest' '-Dsurefire.failIfNoSpecifiedTests=false'
pnpm --dir frontend test
pnpm --dir frontend test -- agent-timeline.test.ts agent-workflow.test.ts agent-result-components.test.ts
pnpm --dir frontend typecheck
pnpm --dir frontend build
node frontend/scripts/visual-review.mjs --production
node frontend/scripts/capture-run-quickstart.mjs
```

本地证据保存在未提交的 `target/` 下：`quickstart-refinement-final-verify.log`、`ux-refinement-frontend-tests.log`、`ux-refinement-final-focused.log`、`ux-refinement-typecheck.log`、`ux-refinement-frontend-build.log`、`ux-refinement-browser.log`、`ux-refinement-design-check.json`。浏览器使用本地合成 fixtures，无真实 API/付费调用；截图和 SHA-256 清单见 [截图目录清单](../../target/agent-run-quickstart-review/manifest.json)。代表截图：[桌面 Run](../../target/agent-run-quickstart-review/evolution-1440.png)、[窄屏 Run](../../target/agent-run-quickstart-review/evolution-390.png)、[窄屏 Quick Start](../../target/agent-run-quickstart-review/quick-start-390.png)。

## 9. 剩余限制

真实 Bookstore 的新指南没有生成，既有指南仍是原产物。模型对名称冲突、推导命令及章节组织的遵从程度，需要用户后续显式重生成验收。较深、较大、非常规命名或不在预算内的启动材料可能被省略；无法安全处理的文件会整份跳过。

实现摘要取已有报告的简短句子（最多五条），不另调用模型重写。缺少完整日志留存的旧事件只能展示其已保存输出，不能恢复历史未保存内容。已知非 Java 的判断依赖已有项目元数据。

## 10. 手动验收与重启

1. 目前服务未重启。在现有前后端终端用 Ctrl+C 停止后，于仓库根目录运行 ` .\setup-delveforge.ps1 `，沿用原配置；如使用自定义镜像，沿用已有 `-AgentImage21` / `-AgentImage17`。该脚本重新构建后端并核验/复用镜像。本次未改镜像或数据库结构。完整启动说明见 [LOCAL_SETUP](../LOCAL_SETUP.md)。
2. 默认打开 `http://127.0.0.1:5173`。进入已有 Run，只读检查：清单独立、普通时间线简洁、成功预检紧凑、费用确认仍在启动对话框，避免点击启动真实 Run。
3. 展开开发者详情，检查命令、退出状态、统计和诊断；展开日志、切换 stdout/stderr、加载更多，检查文本追加和保留上限提示；收起后原始命令不出现在普通视图。检查历史失败与最终源码证据的区分。
4. 查看高级设置：自动 JDK 默认、手动选项含义准确、Maven 根字段范围明确。可用合成浏览器 fixtures 检查成功预检、失败预检和不同结果状态，无需触发真实执行。
5. 打开已有 Plan 的 Quick Start：版本与来源折叠正常，旧文档可读，命令复制保留；在桌面和手机宽度检查排版。
6. 若要验收真实新指南，由用户自行选择「重新生成」（会产生一次付费模型请求）。检查仍叫「纸上书店」，来源包含净化后可用的根 README、开发指南、Wrapper、环境示例和 Compose；比对命令、端口和服务顺序是否有交付文件依据。若必要材料因安全/预算省略，应看到缺失说明而非虚构指令。
7. 指南生成不等于成功启动。实际 Bookstore 启动与业务验收另行执行；本次不修改其 README、数据或既有 Run 材料。
