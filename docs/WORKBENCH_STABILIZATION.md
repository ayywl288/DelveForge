# 工作台迁移后稳定性验收

2026-11-17，Windows 11，本地主仓库 `codex/workbench-model-providers`。基线为 `8b7ef33d69ed`，使用 Spring Boot 4.2.0-RC2、Spring AI 2.1.0-RC2、Java 21 和 Docker Desktop。本报告记录本轮实测。

## A. 实现

| 变更位置 | 行为 |
| --- | --- |
| `SpringWorkbenchModels`、`ModelTransport`、`ConversationAgent` | 验证要求一次合法的原生 Ping、匹配的调用 ID 和空参数，并在下一轮读回当次随机回执。只有提供方明确拒绝工具或推理能力才记录不支持。认证、权限、账单、模型不存在、限流、截断、无效调用或错误回执均可复验，保留已有结论。 |
| `ProviderCredentials`、`V9__review_model_validation.sql` | V8 的负结果缺少失败原因，升级时恢复为待验证，并清除孤立的已保存凭据引用。保存或删除凭据时事务清理对应记录。记录结果前再核对当前引用，防止迟到的验证响应重建旧记录。已有 Run 使用原冻结引用。 |
| `ProviderFamily`、`WorkbenchChatModel`、`workbench-models.json` | 补齐智谱 Flash 的推理设置和已测模型目录。DeepSeek 的推理工具提议仍须携带可重放部件。实测中提供方会给出完整的最终文本而不附新的推理内容，工作台现在接受该终止响应。 |
| `WorkbenchTokenEstimate`、`ProviderCalibration` | 基于本地预算和真实提供方使用量校准工具开销。测试侧可独立运行，自动回归使用本地协议夹具。 |
| `ModelPicker.vue`、`ProviderSettings.vue` | 分模型及思考强度显示已实测、待验证和明确不支持。凭据或端点改变时刷新模型选项与状态。 |
| `AppearanceSwitcher.vue`、`ProjectWorkbench.vue`、`NavigationIcon.vue`、`index.html`、`workbench.css` | 工作台右上角提供日月主按钮和独立的下拉箭头。勾号标记选中的偏好。首次绘制前读取本机偏好，系统偏好变化同步更新代码高亮和图标。窄屏和触控下两个按钮及菜单项的高度至少 44px。 |

回归用例见 [提供方协议](../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workbench/model/ProviderProtocolTest.java)、[模型与凭据持久化](../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workbench/model/RunModelPersistenceTest.java)、[外观](../frontend/tests/workbench-appearance.test.ts)和[模型选择器](../frontend/tests/workbench-models.test.ts)。

## B. 真实提供方

各家使用独立的受控 Ping，一个提议请求加一个回执请求。只有原生工具名、参数、调用 ID、上一轮私有部件和随机回执都得到核对后才计为成功。表中 token 是这两个成功请求之和，输入包含缓存命中。

| 提供方和返回的模型 ID | 推理设置与接口 | 工具提议 / 回执 | 私有推理重放 | 输入 + 输出 = 总量 |
| --- | --- | --- | --- | ---: |
| DeepSeek `deepseek-flash` | high，真实 API | 通过 / 通过 | 首轮 `reasoning_content` 与下一请求内容逐字一致 | 782 + 112 = **894** |
| OpenAI `gpt-5.4-nano-2026-03-17` | high，真实 Responses API | 通过 / 通过 | 同一不透明加密推理项回传，模型继续完成响应 | 365 + 190 = **555** |
| 智谱 `glm-4.7-flash` | `thinking.type=enabled`，国内普通 API | 通过 / 通过 | 首轮 `reasoning_content` 原样重放。回执请求限流后重试成功 | 393 + 118 = **511** |
| Anthropic、Gemini、Qwen、Kimi、OpenRouter | 本地模拟 HTTP | 原生提议 / 回执断言通过 | 按各协议检查相应部件及调用关联 | 无真实账单数据 |

DeepSeek 两次输入为 327、455（其中缓存命中 128、256），OpenAI 为 82、283，智谱为 158、235（各命中 42）。OpenAI 另一次低强度简短探测给出了工具调用，但没有返回可验证的加密推理项。第一次手工传入 OpenAI 凭据时发生转录错误并得到 401，纠正输入后 high 回合通过。智谱使用 `open.bigmodel.cn/api/paas/v4`，回执限流为 HTTP 429 / 1305。国内 Coding Plan 与国际端点此次均未调用。原始思考文本、加密项和密钥不出现在证据中。[DeepSeek 思考模式](https://api-docs.deepseek.com/guides/thinking_mode/) · [OpenAI 模型](https://developers.openai.com/api/docs/models/gpt-5.4-nano) · [智谱 Flash](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash) · [智谱 Coding Plan](https://docs.bigmodel.cn/cn/coding-plan/overview)

## C. 输入 token 校准

下表比较本地估算和同次输入的提供方计数。误差为估算减去实际输入，正数表示预留充足。修正列在原始请求上按新规则重算。七个 OpenAI 样本将 Spring AI 实际组装的输入提交给官方 `/responses/input_tokens`，其余样本使用真实请求的输入 Usage。输出与推理输出不计入校准分母。[OpenAI 输入计数](https://developers.openai.com/api/docs/guides/token-counting) · [DeepSeek 用量](https://api-docs.deepseek.com/quick_start/token_usage/)

| 提供方 | 样本 | 来源 | 原估算 | 修正后 | 提供方输入 | 修正后误差 |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| DeepSeek | `ping` | Usage | 159 | 351 | 327 | +24 |
| DeepSeek | `reasoning-receipt` | Usage | 428 | 620 | 455 | +165 |
| DeepSeek | `short-english` | Usage | 47 | 47 | 38 | +9 |
| DeepSeek | `tool-schemas` | Usage | 457 | 649 | 597 | +52 |
| DeepSeek | `chinese-code` | Usage | 2918 | 2918 | 2034 | +884 |
| DeepSeek | `simple-ping` | Usage | 113 | 305 | 294 | +11 |
| OpenAI | `ping` | Usage | 159 | 159 | 82 | +77 |
| OpenAI | `reasoning-receipt` | Usage | 3423 | 3423 | 283 | +3140 |
| OpenAI | `short-english` | 输入计数 | 43 | 43 | 11 | +32 |
| OpenAI | `chinese-code` | 输入计数 | 2912 | 2912 | 2206 | +706 |
| OpenAI | `tool-schemas` | 输入计数 | 453 | 453 | 205 | +248 |
| OpenAI | `large-tool-result` | 输入计数 | 7160 | 7160 | 3273 | +3887 |
| OpenAI | `two-tool-rounds` | 输入计数 | 649 | 649 | 268 | +381 |
| OpenAI | `cross-provider-visible-continuation` | 输入计数 | 544 | 544 | 221 | +323 |
| OpenAI | `large-chinese-code` | 输入计数 | 63287 | 63287 | 48406 | +14881 |
| OpenAI | `simple-ping-low` | Usage | 113 | 113 | 53 | +60 |
| 智谱 | `ping` | Usage | 113 | 177 | 158 | +19 |
| 智谱 | `reasoning-receipt` | Usage | 368 | 432 | 235 | +197 |
| 智谱 | `short-english` | Usage | 47 | 47 | 13 | +34 |

原规则在 19 个样本中低估 5 个，最大低估为 DeepSeek 简短 Ping 的 181 token（61.6%）。原误差最小值、中位数、最大值为 −181、+77、+14,881。保留通用估算的 25% 余量，对含工具定义的 DeepSeek 请求加 192 token、智谱请求加 64 token，每个请求只加一次。修正后相同 19 个样本的低估数为 0，误差分布为 +9、+165、+14,881。原相对误差范围为 −61.6% 至 +1,109.5%，中位数 +56.6%。修正后为 +3.7% 至 +1,109.5%，中位数 +83.8%。

大中文代码样本的官方输入为 48,406 token，约占该 OpenAI 模型 400,000 窗口的 12.1%。本地将加密推理序列当作文本估算，导致 OpenAI 回执样本估计 3,423、实际 283。跨提供方样本使用合成历史，本地预算包含保存的部件，发送给新提供方的是可见投影。两种输入表示会产生保守偏差。智谱 Flash 的独立分词接口返回 HTTP 400 / 1214，改用实际输入 Usage。额外四工具请求再次限流，故智谱只有三项样本。Anthropic 与 Gemini 有官方计数设施，本次缺少授权凭据。这组观测可解释修正，尚未覆盖独立留出样本、五家未实测提供方及智谱长输入。[智谱分词支持列表](https://docs.bigmodel.cn/api-reference/模型-api/文本分词器) · [智谱错误码](https://docs.bigmodel.cn/cn/api/api-code) · [Anthropic 输入计数](https://platform.claude.com/docs/en/build-with-claude/token-counting) · [Gemini 输入计数](https://ai.google.dev/gemini-api/docs/tokens)

## D. 主机清理

- 对照测试登记、Docker 资源名、标签、挂载源及进程，确认本轮项目、工具和下载三组夹具的所有者为 `df-wb-62d8b57927f3`、`df-wb-3806f424c8b4`、`df-wb-03fc248083ab`。删除测试创建的 79 个容器、43 个网络、19 个卷和 1 个测试专用镜像。测试前与清理后均为 17 个容器、14 个网络、73 个卷。
- 上一轮的三个夹具登记名下已经没有 Docker 资源。保存两轮传输证据后，删除上一轮 `docker-acceptance` 和本轮 `target/stabilization-20261117/docker` 两个专用目录。普通文件逻辑大小分别为 83,912,002 和 83,709,317 字节，合计 167,621,319 字节。未测 Docker 虚拟磁盘的物理回收量。
- 保留主工作台四项旧资源、宣传项目的测试会话、MySQL/Redis/LibreTV 和共享缓存。旧 UI 验收库有一条凭据记录，保留该库。待人工确认的资源有 `df-step4-product-20261028-source`、匿名卷和缺少专属归属证据的旧缓存。
- 核对前后两份数据库 SHA-256。工作台 `data/workbench/control.db` 为 `378e512b2704ee87805fb9bf117c8011ded4699a163dbae5150d4ed89fa55f2f`，旧库 `data/delveforge.db` 为 `3912e56a6373eaa3e913acc5c8ebbe13b0a981bbb9a5d70823298dd0bf5e60d1`。V9 迁移在独立 SQLite 测试库验证。

## E. 回归与复验方法

| 实际命令或检查 | 结果 |
| --- | --- |
| `.\mvnw.cmd -o -B -ntp -pl ':delveforge-domain,:delveforge-application,:delveforge-infrastructure,:delveforge-app' clean verify` | 四个模块。默认 90 项通过，9 个显式启用的 Docker 用例在该命令中跳过。涵盖兼容性、凭据和冻结配置、推理持久化、跨提供方续接、上下文、Run 生命周期和恢复。 |
| `.\mvnw.cmd -o -B -ntp -pl :delveforge-infrastructure -am '-Dtest=WorkbenchDockerTest,ParallelProjectDockerTest,ProjectToolImagesDockerTest,RemoteDownloadsDockerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 用新建并分别初始化的 projects、tools、downloads 数据目录运行。9 项全部通过，0 跳过。[夹具准备](WORKBENCH_RUNTIME_VALIDATION.md) |
| `python -B runtime/workbench/validation/test_file_versions.py` | 文件版本回归 1 项通过。 |
| 在 `frontend` 运行 `pnpm typecheck`、`pnpm build`、`pnpm test` | 类型检查和生产构建通过，38 项组件测试通过。Vite 在受限进程中启动时报 `spawn EPERM`，允许创建子进程后复验。 |
| `node target/stabilization-20261117/appearance-browser.mjs` | 真实 Chrome 加载生产资源和固定对话数据。在 1280、390、320px 验证 17 个状态，涵盖系统变化、一键显式覆盖、恢复系统、刷新、键盘、触屏、减少动态效果、代码高亮和提供方表单。 |
| `node target/stabilization-20261117/appearance-browser.mjs --design-audit` | Impeccable、Hallmark、taste-skill 的源码和浏览器检查。原始输出与两种外观的截图在本机 `target/stabilization-20261117/design-review/`。 |

| 设计检查 | 复核结论 |
| --- | --- |
| Impeccable | 检查组件、工作台样式以及桌面浅色、手机深色的实际页面。四条边线警告来自错误提示、前后差异和 Markdown 引用。两幅页面均提示菜单打开时遮住下方对话，关闭后恢复。外观操作的标签和勾号可见。 |
| Hallmark | 从官方版本 `04977e15` 临时加载，真实浏览器捕获十张图。检查推动了颜色归并、按钮按下态和输入边界加深。最终两条边界警告均测到无独立边框的内部 textarea，输入区的可见边界画在其父容器上。实测该边界相对画布为浅色 3.60∶1、深色 4.52∶1。选中菜单文字相对背景为 6.96∶1、6.68∶1。整页另有原有间距和小字号提示。[Hallmark 审查说明](https://github.com/Nutlope/hallmark/blob/04977e15f3dfd6ee4b74c887dac3eb52665d6c92/skills/hallmark/references/redesign.md) |
| taste-skill | 按应用界面规则检查 1280、390、320px。渲染检查报告工作台既有辅助文字有小于 13px 的情况。外观选项为 13px，Chrome 窄屏触控模拟测得所有主题操作的触控面积至少 44×44px，菜单与页面均无横向溢出。 |

设计检查使用本机安装的 Impeccable 和 taste-skill。复跑 `--design-audit` 时，在 `%TEMP%/delveforge-appearance-hallmark-04977e1` 准备上述 Hallmark 版本的 `scripts/sloplint*.mjs`、`references/gates.md` 及 `puppeteer-core@24`。本次临时工具已清理，截图与检查结果存放在本地验收目录。

真实接口夹具是测试源码中的 [ProviderCalibration.java](../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workbench/model/ProviderCalibration.java)。完成 Maven 验证后，可在仓库根目录取其测试运行时类路径。以下命令会从对应环境变量或本机终端读取凭据。省略第二个参数时会实际生成回复。

```powershell
$report = 'backend/delveforge-infrastructure/target/surefire-reports/TEST-com.ayywl.delveforge.infrastructure.workbench.model.ProviderProtocolTest.xml'
[xml]$suite = Get-Content -Raw $report
$cp = ($suite.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
$probe = 'com.ayywl.delveforge.infrastructure.workbench.model.ProviderCalibration'
java -cp $cp $probe deepseek
java -cp $cp $probe openai
java -cp $cp $probe zai
java -cp $cp $probe openai counts
java -cp $cp $probe zai usage
```

上述提供方夹具仅向已选提供方的官方端点发送固定测试内容。在本机验证生产适配器的实际请求是否携带同一份推理部件。报告只输出状态、用量及脱敏错误码。本次 OpenAI 补测使用仅计数模式完成两个工具批次、跨厂商投影和大中文样本，本机原始 JSON 留在 `target/stabilization-20261117/`。

## F. Git 与原文件

主检出为 `E:\develop\DelveForge`，开发分支为 `codex/workbench-model-providers`。本报告随实现一起提交。基线 `8b7ef33d69ed399a601ef185a72c91fa8e16ebe1`，本地 `main` 为 `13a019d66c19c5084201db91bb3eb170a08d474c`。可用 `git log -1 --format=%H -- docs/WORKBENCH_STABILIZATION.md` 获取本报告对应的提交。

工作区原有 `codex-migration/`、`docs/design/`、`frontend/design/`、`grilling/` 共 131 个未跟踪文件、11,798,467 字节。对已排序相对路径和各文件内容形成的 SHA-256 为 `67259914b6b5b4a992b19c3c6302931108cd53f768ed9fcf41d3a0c94277f709`，与开始时一致。
