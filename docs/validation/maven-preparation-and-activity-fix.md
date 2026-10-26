# Maven 依赖准备快照与活动时间线定向修复

完成日期：2026-10-27（Asia/Shanghai）。分支：`codex/maven-preparation-activity-fix`，起点 `74db524`。
本机完整日志与复现材料：`E:/develop/DelveForge/data/maven-activity-fix-20261026/`。
本轮没有 DeepSeek 调用、付费 E2E、新 Agent Run、外部应用业务修补或计划改写；未合并、未推送。

## 确认的原因与证据边界

在原始 Taskarium 提交 `91f69ab1d7ab3a654814a1971cd3871ab0dffb1d` 的未改动副本中，
用原受信任 JDK 17 镜像、离线 Maven 与原有 `contextLoads` 测试完成确定性复现，Maven exit=0。
使用同一 `sandbox.py` 快照算法比较，唯一变化为：

| 相对路径 | 类别 | mode | 内容 SHA-256 |
|---|---|---|---|
| `database/taskarium_db.mv.db` | CONTENT | 前后均 `100644` | 前：`e29e193af4feef972e233c826afb455c94ce1b921623a218a69bb8c97c4f4725` |
| 同上 | 同一次变化 | 不变 | 后：`5d65d2a66a4be6ca72165d6e07d373e0299411fc2abe613a3d971816d3927d7f` |

快照前后分别为 `f34457768308a4ceda22fca5c089a9a4114369fb59e6db2061d400c82de55414`、
`f7c00c60b8ba2221e419c4dc9895eae03c484e79f287ee3b21cab5b406cfb8b3`。
测试加载默认配置的文件型 H2，并使用 Hibernate `ddl-auto=update`；它会改写版本化数据库。
本次复现没有 Maven `target` 输出遗漏、模块根判断错误、文件模式变化或快照计时字段进入身份计算的证据。
原实现的在线准备直接执行请求的 Maven goal，并与 Agent 共用可写源码卷，因此准备中的测试副作用污染了该快照。

[路径、类别与哈希证明](evidence/maven-preparation-snapshot-diff.json)只包含元数据，没有数据库内容或凭据。
历史 Run 自身已经清理，未保留前后文件清单或其未冻结修改；以上哈希属于本次原项目复现，
不是对历史 Run 变化字节的恢复或追认。新增真实适配器回归在修复前均以
`DEPENDENCY_PREPARATION_MUTATED_SOURCE` 失败，进一步验证同一机制。

## 后端最小修复

变更位于 `DockerAgentSandbox`，不改变 Domain、Application Port、Candidate/Delivery 合同或依赖：

- 在线准备使用带原 Run 归属标签的一次性 `prepare-source` 卷；原 Agent 源码卷只读挂载到 `/source`。
- 可信固定启动代码复制工程后在副本中执行原 Maven goal，保持原有模块、测试选择、受限网络、非 root、
  资源限制及代理环境清空规则。未用跳过测试的方式遗漏运行期 Provider 依赖下载。
- 准备副本中的数据库、生成物乃至意外源码编辑都不传播回 Agent；只有该 Run 的私有 Maven 缓存复用。
  在线执行从来不成为正式测试证据。
- 原 Agent 源码前后快照仍必须一致，初始快照也须为成功结果；没有新增数据库、日志或其他路径忽略规则。
- 副本卷在正常结束/取消的 finally 中移除，归属校验与清理入口也覆盖重启后的残留资源。

这里没有把数据库写入认定为可接受的源码变化，而是让依赖准备不再拥有 Agent 源码写权限。
正式离线检查仍对真实被检源码做完整快照核对，真实修改继续失败关闭。

## 前端原因与修复

`patchSummary` 和当前活动早已把 `apply_patch`、`edit_file` 都视为源码编辑，
但 `AgentTimeline` 的重要操作过滤器只手写排除了 `apply_patch`。
因此真实失败记录的 25 次成功 `edit_file` 同时出现在重要操作和代码修改汇总中。

修复仅导出并复用现有 `isPatchTool` 分类函数，统一排除两种普通编辑明细。
没有删除持久化事件、修改关联与排序，或合并不同工具结果。
当前活动仍显示两种编辑及其运行指示；拒绝、失败、结果不明的警告和终态仍可见。

在真实历史 Run 上刷新页面并展开完整编辑历史进行了视觉检查：

- 重要操作从 32 行降至 7 行：预约、准备、沙箱就绪、开始、两次 Maven 检查和失败终态。
- 代码修改仍为 26 次尝试、25 次应用、1 次冲突；完整明细可以展开。
- 探索仍为 37 次操作：36 次读取、1 次列目录。
- 原 `FAILED / DEPENDENCY_PREPARATION_MUTATED_SOURCE`、30 次模型调用、65 次工具和 635,067 tokens 未变。

[精简时间线与计数截图](evidence/maven-activity-concise-timeline.png)；
[完整编辑历史截图](evidence/maven-activity-edit-history.png)；
[真实冲突详情截图](evidence/maven-activity-conflict-history.png)。
这些是旧事件的新版展示，不是新执行或历史成功状态。

## 验证

| 范围 | 本次实际结果 |
|---|---|
| 原始项目确定性复现 | 原测试 exit=0，但上述受跟踪 H2 文件变化；仅副本被改写，原克隆未动 |
| 修复前真实 Docker 回归 | 修正测试夹具后，2 项均以准备修改源码错误失败；保留 `backend-focused-red.xml` |
| 修复后真实 Docker 回归 | 3 项，0 失败/错误/跳过；见 `backend-focused-green.xml` |
| Taskarium 准备 | 原有测试实际执行，准备完成后 Agent 全量 path/mode/hash 快照相同，原库字节与 Git 干净状态保持 |
| 禁止的源码修改 | 准备副本的源码编辑被丢弃；对只读原输入的写入被拒绝；正式离线测试的真实编辑仍以 `CHECK_MUTATED_SOURCE` 拒绝 |
| 普通 Maven 正向检查 | 常规 target 输出支持，实际测试 `TESTS_PASSED` 且 executedTests>0；前后源码快照相同 |
| 前端定向 | 67 项通过；两种编辑各 100 次长历史、当前活动、拒绝警告、生命周期顺序、重载事件和 Run 切换 |
| 前端全量 | 17 个文件、256 项通过，独立 typecheck 与生产 build 通过 |
| 后端全量测试 | 166 份 XML、1,702 项，36 项门控跳过，实际执行 1,666 项，0 失败/错误；原 verify 在最终 JAR 打包阶段因文件锁退出 1 |
| 全模块最终打包 | 释放旧空闲后端持有的 JAR 锁后，同一源码 `mvnw.cmd -o -DskipTests verify` exit=0；未重复计为全量测试 |

早期回归夹具曾包含计时字段比较和测试缓存初始化权限问题，均已纠正，失败日志保留，
不把这些夹具问题当成产品根因。前端初次红测也有一处状态文案预期不匹配，已按两种真实文案修正；
精确编辑长历史的重复渲染断言确实先失败、修复后通过。

[机器可读验证汇总](evidence/maven-activity-verification.json)记录命令结果、测试计数、源码与最终 JAR 哈希。
本机原始测试、构建日志以及全量 XML 压缩包保留在文首证据目录。

首次全量 verify 的测试均通过，最终 Spring Boot repackage 因旧验收后端持有 JAR 而失败。
只读确认没有活动 Run/Delivery 后，先用 SQLite backup API 保存一致备份，
核对 PID、Java 进程与启动时间后仅停止该旧空闲后端，再完成同一源码的全模块打包。
旧验收后端当前已停止；没有通过改写数据库或忽略打包错误取得成功结果。

## 历史保护、限制与重试建议

历史 Run、原始四步 Plan、旧验收报告及证据保持原值；本轮只读核对旧 SQLite，未改领域记录。
历史原计划快照 SHA-256 仍为 `bc23239993340200a87c740c5bad64e3d8f8a83db0696348ec0780dbb59125a1`。
原任务的 API 用量没有增加。本分支仅包含这两项修复、回归、运行合同更新和调查证据。

**可以在部署本修复后通过正常流程创建新的合法 Run，但不能承诺四步实验已能通过。**
旧 Run 不恢复、不改成成功。原项目测试若继续直接改写版本化 H2 文件，正式离线检查仍会阻止它；
需要由 Agent 在已授权 Step 内自主处理测试隔离，不能由 Codex 手工修目标代码或豁免该文件。
本轮没有自动发起重试，未验证新的 Candidate、Delivery 或最终目标产品可用性。