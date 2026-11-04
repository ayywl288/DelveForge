# 源码执行与模型可见性分离：原随机仓库真实续跑

日期：2026-11-04，Asia/Shanghai。分支 `codex/random-fullstack-lifecycle-fix`，未合并、未推送。

## 结果

**源码保护修正已验证；真实产品演化 FAILED / UNRESOLVED_CHECK_FAILURE，未交付。**

Run `ea9f4a76-daa0-4e9c-ad0b-bab3bbb16ec1` 通过正常 API 进入真实模型/工具循环，
17:09:48 开始、17:36:56 终止。Agent 修改隔离副本后声明完成，系统在冻结阶段因完整后端失败未解决而拒绝。
没有 Candidate、Delivery 或新接受 revision，不声明预约预检功能完成或业务验收通过。

## 改动及边界

原 10 文件阻塞来自把 `mutationProtectedLines()` 的编辑/披露保护直接复用于整文件执行分类。
9 个普通认证测试和 1 个 API 路径文件因此不在执行树内，前端、Maven 检查均无法启动。

工程提交 `320eb8a9cc5bf9b848810e86543e150c90def56b` 分开这两项责任：

- 普通 Java/Kotlin、JS/MJS/CJS、TS/TSX/JSX、Vue/HTML 源码不再仅因 password/token 字段字面量而整份排除。
- 私钥、已知令牌、带字段上下文的认证头、带口令 URI、专用凭据路径仍保守处理；超出现有 256 KiB UTF-8 判定能力的普通源码保留。
- `read_file` / `search_code` 仍完整源码 `sanitizeSource()` 后切片；受保护行写入、前后像和冻结重放不变。
- 输出增加裸 Bearer/Basic 净化，复用原私钥、已知 Key/JWT、连接串和凭据字段规则；工具、预检/准备诊断、Agent 活动及 Candidate 技术证据均经过净化。
- 原 Java 三元表达式修复保留，没有新增安全子系统、依赖、领域状态或数据库迁移。

后续提交 `058548c749fc060c079d58a7713b3927f1697474` 修复另一个实际兼容问题：旧 Surefire 2.22.2 的
`MultipleArtifactsNotFoundException` 未被识别为缺依赖。现在识别旧式缺失列表及 Missing POM 提示，
不把 dependency path 或 `dummy:dummy` 项目上下文当作下载身份，仍使用原受控工件准备、8 轮/32 身份边界。
没有升级目标 POM、Surefire、JUnit 或 Maven 声明。

同一提交保留预检失败的路径/变化类型/摘要元数据，不返回文件内容；公共 `_TITLE` 配置只增加有界文字语法，
归只读 PUBLIC_RESOURCE，不向模型披露、不授予编辑权。密码字段、已知 Key、脚本表达式及未知配置仍按原规则拒绝。

执行仍无网络、不注入主机凭据，联网准备不执行项目生命周期/安装脚本；所有权、CPU/内存/PID、单次超时、
源码身份、Step 授权、Candidate 和 Delivery 规则保持。没有新增人为累计调用、Token 或编码次数停止上限。

这不是完整秘密防护：没有上下文的不透明值、未知格式、编码/拆分/变换或恶意程序输出可能不命中。
公开类型和扫描未命中都不能证明任意内容无秘密；不承诺通用仓库兼容或恶意代码托管安全。

## 真实回归与运行配置

| 检查 | 本轮事实 |
| --- | --- |
| 新回归修复前 | Application 裸认证输出失败；Infrastructure 34 项中 5 failure；旧 Maven 格式 6 项中 1 failure；公共标题 10 项中 1 failure |
| 源码保护完整后端 verify（320eb8a） | 1878 总数，55 跳过，实际 1823，0 failure/error，BUILD SUCCESS |
| 真实 Docker 源保护 | 4 项通过：Maven/Vue 认证夹具、净化读取/搜索/输出、高风险输入拒绝、绕过工具的修改无法冻结 |
| 真实现代/旧 Surefire | 2 项通过：真实失败→修正→重验→新增依赖→Candidate 冻结，正式合成副本不变 |
| 最终相关安全与双端预检 | 174 项，0 failure/error/skip；包含同一原仓库实际双端探针，结果 READY_BASELINE_FAILURE |
| 最终全模块 package -DskipTests | BUILD SUCCESS；此命令不执行测试 |

各轮有重叠，不累加为独立测试总数。合成夹具不是随机产品交付或真实模型响应。
DelveForge 自身前端未修改，本轮未重跑其前端检查；下方 387 项属于原随机目标。

实际 Maven 测试还触发一次 `CHECK_MUTATED_SOURCE`：上传控制器测试在检查副本中新增
`backend/uploads/<UUID>.png`，快照守卫正确拒绝。只读定位到原控制器的 `@Value("${upload.path}")` 后，
仅为本次运行镜像设置公开环境变量 `UPLOAD_PATH=/tmp/delveforge-validation-uploads`。
原配置文件、测试、源文件都没有改写，没有给 uploads 目录加忽略例外，也没有关闭快照检查。
临时数据位于原有受限 tmpfs，网络与资源控制不变。这是外部运行配置修正，不是源保护放行或目标源码修补。

通用 v8 工具镜像（脚本/JDK/Node/npm/pnpm 摘要和版本已按项目启动器核验）：

- JDK17：`sha256:d7d24c97ad286e9486f064c126bb5fb8e0bb09bc1390840a7e9124700073d6dd`。
- JDK21：`sha256:fe852c7f659b030baa2a5033cbeef8f677cc14cdeab30bc5c37f5ce5f479fa5a`。

本次带公开临时路径配置的镜像：

- `delveforge-agent:random-runtime-v8-jdk17`，`sha256:90467c666ae90d6dda0dadea8c6bbdfe8ca50e36b6369c10a6fbc148449456ed`。
- `delveforge-agent:random-runtime-v8-jdk21`，`sha256:0b20e8ec2ee28d2262c61dcbff55f5adb50b7c46f70980a17cb68a23a90d218a`。

共享缓存仍配对 `delveforge-local-agent` / `delveforge-maven-central-cache`；没有改缓存标签或接管其他资源。

## 固定输入与正常产品流程

上游仍为 [校园设备预约系统](https://github.com/Techd81/Campus-laboratory-equipment-reservation-system-based-on-Spring-Boot-Vue/tree/307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05)。
commit `307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05`，tree `8f05690251b35365a5a5d60a5ddcd5d99bc38ec2`，MIT，360 文件。
抽样池、种子和一次选择见[原试验](final-random-fullstack-20261104.md)；没有重抽、换 commit 或降低功能目标。

复用 Plan `7bc1be8e-d40a-4df4-9c45-fb83fe97c932`、Step `adb5edec-22a3-426f-bcbd-8ecf2563938d`、
WorkingCopy `c9086c3b-f5d7-4ec7-89dc-fd7f5218d794`，没有重新发现、生成画像或规划。
原 Step 仍为预约提交前规则预检和失败解释的完整后端/Vue切片。
运行前逐文件核对：原源及正式副本各 360 个文件、index、HEAD/tree/status 均与此前快照相同。
Codex 没有手工修改目标代码、测试、manifest、锁文件、SQL 或正式副本；编码修改仅由真实 Agent 工具产生。

修正后的原仓库预检达到 READY_BASELINE_FAILURE：Maven 实际执行 270 项，5 failure、31 error、34 报告；
前端构建成功，Vitest 387 项通过。单独 typecheck 脚本未声明，build 实际包含 vue-tsc 与 Vite。
sourceView=PROJECTED，withheldSourceCount=0，5 个资源保留、2 个只读公开资源。
基线失败包括缺失的原 start.ps1、过期的测试依赖/集合类型及断言，不把这些结果称为测试通过；冻结仍须有效重验。

正常 API GET 核对原 Plan ACTIVE、Step READY 后，以明确费用确认、JDK17、backend/frontend 启动上述真实 Run。
此前两个失败 Run `8b546160-4029-4f92-bc48-b986a691515c` / `de6a3c53-6b3c-4167-88e8-89c431efdf27` 及事件保留。
旧安全阻塞结论见[此前续跑](random-fullstack-resumption-20261104.md)，不改写历史。

## 实际终态、用量与独立验收

| 阶段 | 本轮实际状态 |
| --- | --- |
| WorkingCopy / 原 Step | 复用已准备、已确认记录，未重做分析/画像/方向/规划 |
| Environment Preflight | 正常 Run 达到可进入循环的基线失败路径；没有伪造 READY 或消除失败事实 |
| 真实 Coding Agent | 100 次请求/响应、127 次工具调用；实际编辑 19 个路径，仅在隔离现场 |
| 后端编译 | 修改中曾失败，修正后实际通过；编译不是测试通过 |
| 最后完整后端测试 | 280 项、0 跳过，3 failure、31 error，TESTS_FAILED |
| 最后选择性后端测试 | 46 项通过；不覆盖完整套件，不能消除全量失败 |
| 前端检查 | 修改中构建曾通过；最后 Vitest 398 项通过。构建通过发生在更早 snapshot，不冒充最终 snapshot 的重建 |
| Candidate 冻结 | MODEL_COMPLETED 后进入 FINALIZING，被 UNRESOLVED_CHECK_FAILURE 拒绝，无 Candidate ID |
| Delivery / 计划收尾 | 未执行，无 Delivery ID / 接受 revision；Plan ACTIVE、Step READY、WorkingCopy READY |
| 独立业务验收 | 未执行：没有正式交付的软件状态可验收，不把隔离补丁或测试通过当作已交付产品 |

最后前端测试、完整 Maven 失败及 46 项选测通过绑定同一 snapshot：
`51c50697a8e9fe9d70153f67168c0cdcfba70186d22122709a223b4b61bbd9f2`。
因此拒绝不是因为简单丢失了成功证据；该状态的完整后端本身仍失败。
基线/后续失败均保留。工程操作没有替 Agent 删改目标测试、调整预期、直接写数据库或放宽冻结标准来改造结果。
Codex 没有修补 Agent 生成的目标代码；其测试修改和保留补丁没有经过独立完整质量认证，不能以模型自报完成作验收。

最终只读核对：原源与正式副本的 360 个物理文件、index、HEAD/tree/status 均与本轮开始完全相同。
currentRevision=lastVerifiedRevision=原固定 commit。Run occupied=0、retained=true，失败现场及全部旧 Run/事件保留，
项目容器均已停止。本轮临时 DelveForge 服务也已停止，进程内凭据随进程释放；没有删除保留材料。

停止原因是实际终态和未解决检查，不是累计次数或 Token 达到人为上限；没有追加盲目付费重试。
工程合并可单独评审，不能据此宣称自主交付或产品就绪。后续若要清理上游基线，应另行确定授权范围，
不在本任务中把原 Step 偷换为无界的全仓库修复。

历史六个 Provider 响应：prompt 79,445 + completion 21,650 = total 101,095；reasoning 15,678 已包含在 completion。
本轮确定性/适配器探针均为 0 Provider 请求；真实编码 Run 新增 **100 次模型请求/响应、6,299,421 Token**。
历史与本轮已知 Token 合计 **6,400,516**，不是费用估算；不会重复加 reasoning。

可审阅的[结构化证据](source-protection-execution-evidence-20261104.json)包含阶段、实际跳过数、日志摘要、
真实检查活动、镜像身份、最终领域状态及两个仓库的完整性摘要，不附原始源码或模型对话。
本地原始材料保留于 `data/random-fullstack-20261104/source-execution-*` 与
`target/source-execution-20261104/`；真实预检 JSON 在 infrastructure 的 `target/`，失败隔离现场在对应 agent-runs 目录。
