# Homer 原计划继续执行与独立产品验收

2026-11-03。**ENGINEERING_READY=YES；AUTONOMOUS_DELIVERY_READY=YES；PRODUCT_READY=NO。**
四步均已正式交付、Plan COMPLETED、WorkingCopy CLOSED；默认数据源/错误汇总缺陷与未核对的导出文件阻止完整产品验收。

本轮使用 `a7438d54ad09d4e43ecc97fdd57a6ca64c6805ae` 的最终工程实现、原持久化画像/方向/四步计划、真实 DeepSeek 和正常产品 API。没有重新 Discovery、换库、重新规划、直接改写领域数据库或外部代写 Homer。

用户先明确确认预算充足并授权四步试验，首轮失败及工程修正后又明确要求“继续”。API key 仅传给独立 Java 服务，不进入项目容器、源代码或提交。模型为启动记录中的 `deepseek-flash`，没有启用 thinking 或替换 Coding Agent。

复用 Profile `51d6bde5-465e-4343-b1d3-6e78f1a5d9fa` revision 18（已确认虚构家庭人设）、RepositoryProfile `0851dac0-9f33-4789-b229-b6c2bd15bded`、Direction `c9091d9a-55b3-47fc-a6aa-9ddc72fbc51d`、Plan `ca223f53-2a31-4772-8293-ca8e500a1750`、WorkingCopy `1ee3c3c5-125a-4937-906e-f2c2d60db83a`。

先前 88 次调用的失败和本任务首轮 32 次调用的 GENERATED_OUTPUT_AMBIGUITY 失败均保持原终态；当前成功不回写历史。工程根因、设计、实际安全负例和 Wrapper 验证见 [工程报告](sandbox-script-evolution-20261103.md)，首轮失败见 [重试历史](autonomous-homeops-retrial-20261103.md)。

## 执行与交付证明

| Step | Run | Candidate | Delivery revision | 最终 Node 测试 |
| --- | --- | --- | --- | ---: |
| 1 | `2bbed3d3-7473-4b6d-9270-5f8e97fda0f6` | `2629d037-2527-4d8c-a4c5-20e1789ed698` | `927284b271b905bfdeaca48a38a1083ed11cb1ea` | 17 |
| 2 | `4d98b27c-dd1d-4033-8e37-965a1cad001c` | `4c9ccf8a-01b1-4ebc-bb63-bee85cba0042` | `d3c2920d36662395080ad5f333e4278259c8a96b` | 34 |
| 3 | `7a2fcc6e-94cd-4fdf-b110-1ddf406bc601` | `fe4785b1-3929-4677-9963-2c198b8cf491` | `5fe670448dc26890be696304a6312130aa6f3b10` | 55 |
| 4 | `5764f004-8895-4f4e-abb7-6a538294773b` | `1263f959-5acd-4f01-afeb-890b5e4ce911` | `72003f5edb0f993301ab432f3467a71f9d758941` | 67 |

上述数量是每步累计测试套件的最终执行数，不能相加当成独立测试数量。四步构建也在最终 snapshot 上通过，build 始终 executedTests=0。

- Step 1 实际测试轨迹：1 项/1 失败 → 15 项/1 失败 → 15 项/0 失败 → 17 项/1 失败 → 17 项/0 失败。旧失败和 stale 标记完整保留。
- Step 2 实际测试轨迹：33 项/1 失败 → 33 项/0 失败 → 33 项/0 失败 → 34 项/0 失败。旧失败和 stale 标记完整保留。
- Step 3 实际测试轨迹：52 项/2 失败 → 53 项/5 失败 → 54 项/2 失败 → 55 项/0 失败 → 55 项/0 失败。旧失败和 stale 标记完整保留。
- Step 4 实际测试轨迹：62 项/0 失败 → 66 项/1 失败 → 66 项/0 失败 → 67 项/1 失败 → 67 项/1 失败 → 67 项/1 失败 → 67 项/1 失败 → 67 项/0 失败。旧失败和 stale 标记完整保留。

最终完整 Git tree 为 `5cd9f7eda6a9e98fe90ef0bd67a1982a1a21e6ba`。相对原版实际变化 14 个文件、增加 4102 行；源代码由真实 Agent 产生。未添加 Vitest 或 POM，仍使用声明的 Node 测试脚本。

每步只在前一步正式交付后经正常 confirm/start API 启动，后续模型输入继承接受的 C。测试失败由 Agent 自行修改私有源码并重验；最终 Candidate 的当前测试和构建绑定同一 finalSnapshot。交付验证 C 的 parent=该步 baseline，tree=Candidate.expectedTree，正式副本干净。外部没有修补产品源码或替换模型输出。

试验辅助客户端最初在自动交付尚未建 journal 时同时提交，观察到 DELIVERY_CONFLICT/409；另一次把交付尚不存在时的空响应当成 JSON。原客户端错误文件保留，改为等待自动 journal 并从现有 Run/Candidate/Delivery 接续，没有重复启动前三步模型或改写服务状态。

## 独立产品验收

仅从接受的 Git 修订导出源码到独立容器，以冻结依赖执行项目 pnpm build。非 root、无网络、根只读、无 key/socket、资源限制；输出只用于独立静态页面，不回写原库、正式副本或 Candidate。检查构建前后源码摘要一致；最终依赖元数据必须与捕获的冻结准备元数据相同。

静态产物使用仓库提供的 *.dist 配置示例初始化部署文件；这一步是文档约定的部署准备，不是补写源码。演化前同样初始化原始示例配置。浏览器只操作本机独立页面和虚构验收数据。

| 旅程 | 实际结论 |
| --- | --- |
| 默认导航进入看板/内置示例 | FAILED：正常链接可达，但服务 mixin 将 #ops-board 导航地址用作 endpoint，fetch 到页面而非记录；报记录需要列表 |
| 读取失败后的家人汇总 | FAILED：没有本地记录时，读取错误与现在无需操作/本周没有必须处理的事同时显示 |
| 空表单校验 | PASSED：名称及日期/间隔错误提示，未新增记录 |
| 创建与逾期/家人汇总 | PASSED：新建备份，2026-10-01 +7 天，显示 2026-10-08 到期、过期 26 天及需要林乔处理 |
| 编辑与刷新持久化 | PASSED：改名后保存并刷新，事项及原备注恢复 |
| 完成与日期重算 | PASSED：完成日期改为 2026-11-03，下次 2026-11-10，状态正常且汇总同步 |
| 备注及稍后提醒 | PASSED：保存家人备注，提醒延期 3 天到 2026-11-13；后续刷新仍恢复 |
| 损坏 JSON 导入 | PASSED：解析错误可见，现有事项和汇总未改变 |
| JSON 预览与二次确认恢复 | PASSED：先预览新增 1 条，首次点击只进入确认，二次点击才恢复；原备份保留 |
| YAML 恢复与刷新持久化 | PASSED：导入第二个续费事项并二次确认，刷新后 3 条本机记录仍在；2 条快到期且家人汇总一致 |
| JSON/YAML 导出按钮 | UI_FEEDBACK_ONLY：JSON 显示已导出 1 条、YAML 显示已导出 3 条；下载事件等待超时，未取得可核对文件，不称内容/往返通过 |
| 显式 endpoint 的文档部署配置 | PASSED_SUPPLEMENTAL：仅在另一个独立静态部署副本启用文档已有 endpoint，2 条示例正确读取及汇总，无 console error；默认副本保持失败 |

**产品阻塞点：** `src/mixins/service.js:36` 的既有 `this.endpoint = this.item.endpoint || this.item.url` 与新组件 `OpsBoard.vue:671` 的 endpoint 判断组合，使默认 `url: #ops-board` 被当成记录地址；`fetchBaseRecords` 在 687–696 行将页面正文交给记录解析器。catch 清空 baseRecords 并 rebuildBoard 后，家人汇总把“数据未知”展示为“无需操作”。这是独立浏览器及接受修订源码共同确认的集成缺陷，67 项技术测试没有覆盖。

启用已有文档的 `endpoint: assets/ops-records.yml` 能读到两条配置记录，验证了原因和有限部署路径；它只是另一个静态配置观察，不修补接受源码、不取代默认失败，也不提升 PRODUCT_READY。导入续费记录还显示“建议间隔 0 天”，保留为界面瑕疵。

没有继续付费模型重试，也没有给已完成的原 Plan 制造第五步授权或外部代修产品。因此本轮无法声明完整端到端产品成功。

本地截图：[演化前](../../target/script-evidence-20261103/external-review/before.jpg)、[默认首次失败](../../target/script-evidence-20261103/external-review/after-default-error.jpg)、[实际操作后的最终页面](../../target/script-evidence-20261103/external-review/after-viewport.jpg)、[显式数据源补充观察](../../target/script-evidence-20261103/external-review/configured.jpg)。完整 DOM、独立输入文件和构建结果保留在证据目录。

独立辅助构建最初有依赖复制用户权限及 tmpfs noexec 错误，修正复制用户和临时目录执行标志后再构建；原错误日志保留。这些辅助错误不改写真实 Agent 检查结论。

## 用量、历史与完整性

四个成功 Run 共 219 次真实模型调用、271 次工具调用；输入 8,360,792、输出 77,958、合计 8,438,750 tokens，供应商报告 cache-hit 8,050,304。连同本任务首轮失败，共 251 次模型、318 次工具、9,154,710 tokens；更早 88 次失败为历史，不混入本次新增用量。

原库 232 文件在浏览器验收及清理后再次逐项 SHA-256 比较完全一致，HEAD 仍为 `daa017dfe1ea8d0875697aede091319b6134bb4b`，Git 干净。正式副本干净，HEAD/currentRevision/lastVerifiedRevision 均为 `72003f5edb0f993301ab432f3467a71f9d758941`，CLOSED。
原三条失败 Run 行及 544 条事件与一致备份完全相同，旧 retained=1 的失败现场未删除。四个新 Run 均 occupied=0、retained=false。独立 Java 和三个静态服务已按 PID/启动时间停止，新增验收容器/卷全部按所有权标签清理；原始 API、日志、截图和导出静态产物仍在本地。


实际账单金额 UNKNOWN，供应商 cache-miss 未报告；不由余额差或字符数推算费用。使用已存在公共缓存，不称冷缓存实验。没有新 Discovery/规划调用或换库重试。

## 范围与限制

仅证明本次 Homer/Vue/Vite、npm/pnpm、Node/Vitest 声明范围内的能力，更多框架、私有 registry、安装期脚本、自定义运行时和覆盖 NODE_OPTIONS 的包装没有因此取得支持。结构化测试证明实际执行和当前源码身份，不证明业务测试充分性。浏览器检查是外部验收，没有在 DelveForge 内新增 Browser Use、业务验收 Agent 或部署平台。

未推送或合并。保留用户 frontend/design/、grilling/；没有修改旧失败报告。


结构化证据：[JSON](autonomous-homeops-continuation-20261103.json)。原始证据根目录：`target/script-evidence-20261103/product-trial-continuation/` 与 `external-review/`。工程提交为 `3914cf9`、`a7438d5`；本报告随后单独本地提交，不改变真实运行基线。
