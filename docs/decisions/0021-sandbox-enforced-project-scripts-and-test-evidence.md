# ADR-0021 — 隔离执行项目脚本，独立归一化测试证据

日期：2026-11-03。状态：Accepted（当前用户任务授权）。

## 问题与现有边界

Homer 的真实 Agent 添加了 Node 原生测试，但旧 frontend.py 只接受若干 Vitest 命令字符串。
因此没有启动测试，配置拒绝却形成了无法由构建消除的未决测试失败。此前 IT-Tools 也暴露同类问题。
继续增加脚本字符串例外不能解决项目包装、组合命令和测试基础设施差异。

Application 仍只授权当前已确认 Step；Agent 不能提供宿主命令。正式 WorkingCopy 只有 Delivery 可以修改。
RepositoryContentPolicy、可信 R、执行投影 E、源码 snapshot、不可变 Candidate 及 Git/SQLite 接受边界保持。

## 决定

1. 保留 run_frontend_checks 的 directory/typecheck/build/test 接口。根据实际 package.json 选择现有脚本名称，
   用镜像固定的 npm/pnpm 执行 run，不对脚本内容建立安全语法白名单。项目脚本及其 shell 都是不可信代码。
2. 联网准备仍只使用经过筛选的 manifest 和受完整性约束的锁文件；不携带 scripts、hooks、项目 pnpm 配置或凭据，
   禁用安装生命周期。执行容器没有网络，依赖来自停止写入者后的只读准备卷，再复制到一次性工程。
   pnpm 12 使用 pnpm_config_pm_on_fail=ignore，避免离线检查自动取得项目指定包管理器；verifyDepsBeforeRun=false 禁止脚本启动前自动重装依赖。
3. 所有脚本进程受非 root、只读容器根、cap-drop、no-new-privileges、内存/CPU/PID、输出和超时约束。
   前端不再挂载 Maven 缓存；禁用 core dump。PID 1 工具在报告和源码核对前杀死、回收容器内剩余进程，
   包括脱离原进程组的后台进程；Java 的取消/失败清理仍销毁检查容器及一次性卷。
4. 测试报告不参与执行授权。受信镜像内的 Node preload 在实际 Node --test 或 Vitest CLI 入口附加单次结构化报告，
   支持保持 NODE_OPTIONS 的 npm/pnpm、shell、Node 子进程包装及组合。Node 使用内置 JUnit，Vitest 使用 JSON；
   每次 runner 产生独立报告和退出记录，报告数量、大小及计数有界。Vitest 汇总须匹配实际 assertionResults；
   Node 按实际 testcase 计数，避免嵌套 suite 重复累计。整体脚本和 runner 均成功、报告完整、执行数大于零才是 TESTS_PASSED。
5. 其他脚本仍可执行；没有已采集 runner 报告的成功退出是 TEST_EXECUTION_UNVERIFIED，不生成通过或测试计数。
   runner API、自定义运行时或覆盖 NODE_OPTIONS 的包装可能无法采集，明确反馈，不强迫安装 Vitest。
   报告证明工具记录的测试执行，不证明断言的业务充分性，也不是对恶意测试程序的密码学认证。
6. evidenceVersion=3 为前端增加 executionStatus、runner、configurationSnapshot。明确 NOT_STARTED 的配置/环境拒绝
   与已执行失败分开：前者保留历史但不制造测试失败；CHECK_NOT_AVAILABLE 和未验证成功均不能清除真实失败。
   准备失败继续阻止冻结，只有同工程同目标的当前有效检查可解决；实际测试失败须有匹配 runner 的当前通过证据。
   旧 v1/v2 仍可读，缺少未启动事实的历史拒绝继续保守解释，不改写旧 Candidate 或 Run。
7. 复用 describe_checks：返回当前工程、管理器、goal、脚本名、不可用原因、报告路径说明与配置摘要。
   执行反馈重读能力并指示配置是否变化。依赖目录不在 Agent 源码视图；只读查询返回拒绝及操作说明，
   不因询问未物化依赖而终止 Run，不读取依赖内容或授予写入。安装复用键也包含配置文件，避免只改 hoist/peer 设置却复用旧依赖。

工具协议升级 v7，启动脚本的输入摘要及镜像内容核验包括新增 preload。旧镜像在传源前拒绝。
Maven 检查与其报告归一化保持独立，不要求纯前端存在 POM。不新增 Port、依赖、数据库结构或领域生命周期。

## 替代方案与取舍

- 扩大 Python 语法白名单：仍会拒绝合法包装，且无法证明识别到的代码安全，拒绝。
- 只执行退出码：无法区分构建、零测试和实际测试，拒绝。
- 改造任意项目为 Vitest：把工具偏好变成产品依赖，拒绝。
- 通用执行框架或宿主 shell：超过当前任务边界，拒绝。

隔离执行增加了可执行脚本种类，但没有放开网络、主机文件或正式副本写入。
支持范围仍为既有 Vue 3/Vite 单工程、公共 registry、npm/pnpm 受支持锁格式及固定运行时。
安装期脚本、私有依赖、凭据、自定义工具链和外部服务需求仍可能不可用，不通过扩大权限解决。

固定 Node 22.16.0 实测 node --test tests/ 会报告 MODULE_NOT_FOUND，不能把目录自动改写成测试集合。
Agent 应根据实际错误修正为有效文件、shell 展开的 glob 或 Node 默认发现；检查器不代修产品源码。

参考：[Node 测试报告](https://nodejs.org/download/release/v22.16.0/docs/api/test.html)、
[Vitest reporters](https://vitest.dev/guide/reporters.html)、
[pnpm 11 配置变化](https://pnpm.io/blog/releases/11.0)。真实验证结果另记，不由本 ADR 预先宣称通过。

当前运行与工程选择说明统一维护于 [LOCAL_SETUP](../LOCAL_SETUP.md#agent-build-preflight)；本 ADR 保留设计理由及当时版本语境。
