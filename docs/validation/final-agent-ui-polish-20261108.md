# Final Agent Run UI Polish 与 Quick Start 内容细化

日期：2026-11-08。分支：`codex/final-agent-ui-polish`。实际基线为已合并到 main 的 `4594c05b8879a595d1cf211f8adce246cb1a2e17`。
本报告随单个本地提交交付，完整哈希见交接回复或 `git log -1`；不 merge、不 push、不改写已有提交。

## 1. 根因与真实记录

只读查询现有数据库确认最新成功 Run 仍为 `08b82f99-d14c-40a1-82e7-02f00e81156a`，最终指南绑定 revision
`70a7c82bba382efc4f09fd2ed2e32e1b30057b02`。预检事件顺序为 4300 STARTED、4301 ENVIRONMENT_PREFLIGHT READY、4302 COMPLETED（1422 ms）。

前端原来逐条展示开始/结束事件，并把「Run 已终止 + 历史 STARTED」映射成 interrupted。成功 Run 也属于 terminal，因而出现「进行中 / 已中断或取消」与后续成功同时显示。后台既有事件本身没有取消。

其他问题来自展示层：原始列表直接循环渲染所有事件和 summary JSON；历史命令失败数量在普通视图使用警告样式；时间线设置 `max-height: 34rem; overflow: auto` 并使用内部 scrollTop 跟随。

Quick Start 原提示要求名称冲突时说明差异，会诱导输出包名与 Compose 项目名等无关内容；没有明确要求判断宿主机与容器数据库是否为互斥路径。

## 2. 修改文件

| 文件 | 修改 |
| --- | --- |
| `frontend/src/components/AgentTimeline.vue` | 移除原始事件 dump，历史失败计数仅在开发者详情；页面滚动、40 条增量展开、回到最新和不抢阅读位置的实时跟随。 |
| `frontend/src/components/agentEventPresentation.ts` | 解析已有预检状态及 READY 结果，按 stage/directory/goal 展示最新事实，缺少终态保持未知。 |
| `frontend/src/components/AgentActivityRow.vue` | 使用合并后的预检标题，避免历史开始标题与最新结果矛盾。 |
| `frontend/src/components/AgentEventDetails.vue` | 仅长技术输出保留有界滚动。 |
| `frontend/src/views/EvolutionView.vue` | 历史列表移除固定纵向高度，窄屏仍保留横向导航。 |
| `frontend/tests/agent-timeline.test.ts` | 状态映射、合并、缺失结果、取消、失败、无原始 dump、历史计数、实时跟随、500 条初始历史的渲染限额。 |
| `frontend/tests/visual-fixtures.ts` | 合成场景包含真实形态的 STARTED → READY → COMPLETED 序列。 |
| `frontend/scripts/capture-run-quickstart.mjs` | 新增 `--polish`，另存 6 张截图并检查预检、滚动和折叠状态，默认旧输出路径保留。 |
| `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/quickstart/QuickStartService.java` | 仅调整独立 Quick Start 请求中的内容约束。 |
| `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/application/quickstart/QuickStartServiceTest.java` | 宿主机优先、Compose 优先、文档冲突三种材料的生成请求回归。 |
| `docs/decisions/0026-factual-completion-and-plan-quick-start.md` | 更新现行呈现与指南生成约束。 |
| 本报告 | 原因、证据、验证、限制及验收说明。 |

## 3. 原始事件与结构化诊断

Run 页面及开发者详情均不再显示「原始事件记录」或其 JSON 列表，没有用另一份 dump 替代。原有 Run ID、时间、状态、模型/tool/token 用量、命令历史、修改历史、环境探针和结果证据保留。

没有修改事件写入、数据库表、存储适配器、事件读取 API 或 SSE。事件仍供结构化展示使用；内存中的事件也没有被删除或改写。

## 4. 预检映射

STARTED / IN_PROGRESS 表示进行中；COMPLETED / SUCCESS 和 READY 表示完成；FAILED 与保存的受阻结果显示失败；显式 CANCELLED / INTERRUPTED 保留取消/中断。状态缺失或 Run 已结束但只有开始记录时显示「结果未记录」，不推断取消或成功。

同一 stage、directory、goal 只显示最新持久事实；整体 ENVIRONMENT_PREFLIGHT 与 PREFLIGHT_PROGRESS 归为同一活动。原有子阶段仍按工程/目标区分。不增加后台状态或协议。

## 5. 历史失败与滚动

普通时间线移除累计失败次数警告。开发者详情标注「历史命令尝试 N 次未通过（不等同于当前失败测试）」。安全阻塞、Run 失败、最终源码验证及限制继续使用原展示，不把 Shell 失败解释为失败测试，也不修改 v8。

时间线随主页面自然展开，默认只显示最近 40 条重要活动，每次增加 40 条；首次在屏外加载 500 条事件仍只渲染 40 条。读者浏览较早内容时不自动跳页，新事件保留阅读起点，可显式回到最新；首次加载不主动滚到活动底部。长命令输出仅在开发者详情中最多 28rem，stdout/stderr、分页和截断事实保持。

## 6. Quick Start：真实仓库观察及改动

只读检查上述固定交付 commit 的根 README、`document/开发指南.md`、`docker-compose.dev.yml`、后端 application 配置和 `.env.example`：

- README 第 114–134 行与开发指南第 12、35–40、72 行明确要求宿主机 MySQL，端口 3306；因此文档的主路径是宿主机 MySQL。
- 两份文档同时建议不带服务选择的 `docker compose -f docker-compose.dev.yml up -d`，却将 Docker 基础设施描述为 Redis/Milvus 等。
- 实际 Compose 定义了 mysql 服务，映射 `3306:3306`；全量 up 也会启动它。宿主机 MySQL 与该容器同时启动会竞争宿主机 3306。
- `application.properties` 的数据源引用 `${DB_URL}`；环境示例指向 `localhost:3306/bookstore`。这只能说明连接目标，不能证明应同时启动两套数据库。

因此真实材料存在「文档宿主机主路径」与「Compose 全量命令实际包含 mysql」的冲突。新请求要求保持文档主路径，简短指出冲突，将 Compose 数据库作为替代路径，明确二选一和有证据的端口冲突；只有服务清单和依赖支持时才给出选择服务的推导命令。其他仓库若明确推荐 Compose 则按其文档优先，不硬编码 Bookstore。

无关包名/Compose 名称差异不再要求解释；仅在影响实际启动参数时保留。应用名称、材料安全与净化、固定 revision、一次独立 AiGateway 调用、页面结构、复制按钮及折叠来源均保持。未修改 Agent 系统提示、源 README、正式交付材料或已有指南。

## 7. 验证结果

- 前端相关测试：**4 文件、170 项通过**，包括成功、失败、显式取消/中断、缺失终态、实时更新、历史顺序与渲染数量。
- `pnpm --dir frontend typecheck` 通过；最终 production build 也执行 `vue-tsc -b`，**101 modules，构建通过**。
- 因 QuickStartService 的请求内容改变，运行后端定向 verify：**51 项通过，0 失败/错误/跳过**。QuickStartServiceTest 6、QuickStartSourcesTest 6、DeterministicRepositorySecretPolicyTest 27、RepositoryContentPolicyTest 12。四模块及可执行 JAR 打包通过。
- 后端使用 `target/final-agent-ui-polish-build` 源码副本，避免修改运行进程占用的默认 JAR；未重启正式服务。没有运行无关完整套件或 Docker 集成。
- 浏览器在 **1440px / 390px** 检查生产构建，另存 **6 张截图**：完成页、开发者详情、Quick Start。检查无横向页面溢出、无原始事件 dump、预检仅一条成功活动、时间线没有固定高度滚动、原始命令没有重复。原有 10 张截图未覆盖。
- `git diff --check` 通过。初轮前端旧文案断言及后端新测试的 Mockito 重设方式有失败，已修正后通过上述最终回归。

命令：

```powershell
pnpm --dir frontend test -- agent-timeline.test.ts agent-workflow.test.ts agent-result-components.test.ts quick-start.test.ts
pnpm --dir frontend typecheck
pnpm --dir frontend build
.\mvnw.cmd -f target/final-agent-ui-polish-build/pom.xml verify '-Dtest=QuickStartServiceTest,QuickStartSourcesTest,DeterministicRepositorySecretPolicyTest,RepositoryContentPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false'
node frontend/scripts/visual-review.mjs --production
node frontend/scripts/capture-run-quickstart.mjs --polish
```

本地日志为 `target/final-polish-frontend-tests.log`、`final-polish-typecheck.log`、`final-polish-build.log`、`final-polish-backend-verify.log`、`final-polish-browser.log`（均在 target 下）。
截图与哈希清单：[manifest](../../target/final-agent-ui-polish-review/manifest.json)、[桌面完成页](../../target/final-agent-ui-polish-review/delivery-1440.png)、[窄屏开发者详情](../../target/final-agent-ui-polish-review/developer-390.png)、[窄屏指南](../../target/final-agent-ui-polish-review/quick-start-390.png)。截图为合成数据，不是新的真实模型输出或业务验收。

## 8. 剩余限制与手动验收

付费模型调用、真实 AgentRun、Bookstore 启动/E2E 均 **NOT RUN**。生成请求测试证明材料与指令正确，不证明真实模型每次均遵从。真实旧指南未重生成；新内容约束需要下次显式生成才生效。

完整 Tree 元数据枚举的性能边界与上轮一致，没有改 WorkspaceReadPort；事件接口仍保留现有最近 1000 条加载范围。单独的事件导出/高级查看器不在本次范围。

1. 先停止现有前后端，运行 ` .\setup-delveforge.ps1 ` 沿用原配置重建启动；本次无数据库迁移或镜像资源变更。
2. 打开原成功 Run：确认普通时间线无累计失败黄色提示；展开开发者详情，检查预检为一条成功活动，没有 STARTED 被显示为取消，也没有原始事件列表。
3. 检查统计、命令、编辑历史、stdout/stderr 和加载更多仍可用；检查最终源码结果与历史尝试区分。失败及取消 Run 应保留真实状态，只有开始记录的预检应显示结果缺失。
4. 桌面与手机宽度查看长活动列表：主页面纵向滚动、每次展开更多活动、回到最新；长技术输出可局部滚动，普通时间线没有第二层滚动条。
5. Quick Start 可先只读检查旧指南的结构和复制功能。若用户决定验收新生成内容，再自行点击重新生成（一次付费调用），核对没有无关包名差异，MySQL 宿主机/Compose 路径分开且提示真实端口冲突。不要将生成指南视为已执行启动验证。

`codex-migration/`、`frontend/design/`、`grilling/` 保留；原 Bookstore、正式 Run/Delivery、授权与 v8 合同均未修改。
