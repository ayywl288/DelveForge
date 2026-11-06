# ADR-0022：原生依赖准备与模型前环境预检

日期：2026-11-03。状态：Accepted for the explicitly authorized task；实际验证单独记录。

## 问题与既有约束

旧 Git 兼容检查把 Maven 根数量、profile、锁文件等构建声明当成全仓库准入条件。
另一方面，CodingAgentService 在 prepare 后直接调用模型，describe_checks 只说明声明，不能证明工具和依赖可执行。
旧 Maven 联网准备还会执行项目生命周期。继续增加静态格式例外无法解决这些问题。

原资产只读、固定 Git 身份、A/P 来源、执行投影、逐 Step 授权、源码 snapshot、不可变 Candidate、
Git/SQLite Delivery 和已知失败重验规则继续有效。预检不赋予软件可信 revision，也不扩大修改权限。

## 决定

安全准入、环境就绪、源码/测试结果分开。安全 Git 可分析、准备 WorkingCopy，即使构建布局未知或不可执行。
生成物仍仅逐项证明后在独立副本准备；maven-generated-v1 继续表示这一准备政策，不表示构建就绪。

Application 在既有 PREPARING 阶段调用 AgentSandboxPort.preflight，然后才进入模型循环。
预检通过既有 Run 事件持久化 PREFLIGHT_PROGRESS：整体预检、后端/前端检查和依赖准备的开始、结束、
失败及耗时。它们不计为 Agent 模型或工具调用；事件重读可恢复当前阶段，不新增监控系统或领域状态。
READY、READY_BASELINE_FAILURE 可进入循环；ENVIRONMENT_BLOCKED、SECURITY_BLOCKED、SELECTION_REQUIRED 停止。
新 Run 初始 tokens 为已知的 0；真正发出模型请求时恢复未知用量语义，收到 usage 后按既有规则累计。
不回填旧 Run 的用量，不新增领域状态、数据库迁移、Port 接口、工具或 Aggregate。

每次探针都使用固定 R 的执行投影、实际固定镜像和与后续工具相同的一次性检查路径。
声明或零退出不足以证明就绪。Maven 要有实际 compiler/Surefire 阶段或有效测试报告；前端需要实际项目 Node
入口执行记录或有效测试报告。明确的编译/测试失败可修复；缺依赖、外部服务、未知失败保守阻塞。
编译失败未到达测试阶段时显式记录 TEST_RUNNER 尚未证明，不把 build 当成 test。

## 依赖边界

项目 Maven、go-offline 及生命周期始终无网络。优先使用实际构建错误的缺失身份，固定 dependency-plugin 3.8.1
的 go-offline 仅在未获得身份时用于离线发现缺失项；错误上下文中的当前项目身份不能成为下载对象。
受控坐标从实际离线诊断中提取、去重，最多 8 轮、32 个身份；联网进程只加载系统生成的空 POM，
调用固定 dependency:get，不读取项目模型、.mvn 或执行项目生命周期。实际检查仍只以离线结果为准。
未证明的缺失坐标、不可用工件或预算耗尽不会改为联网运行项目。

npm 2/3 锁、shrinkwrap、无锁解析，以及 pnpm 6/9 锁和普通 workspace 由固定原生管理器处理。
联网安装只接收筛选后的 manifests、锁和安全 workspace packages 声明；忽略安装生命周期和 hooks。
无锁解析产生的锁只留在本 Run 私有准备卷，记录 SHA-256 和较弱的可复现性，不进入原资产、正式副本或 Candidate。
脚本仍在无网络副本执行，使用停止写入者后的只读准备卷。

工件代理固定官方 registry 上游；已知公共源的 URL 按包名/版本校验后映射到代理，保留 integrity。
原生无锁解析收到的 metadata tarball 同样映射，不允许其触发任意地址、重定向或凭据转发。
固定 Node 22.16.0、npm 10.9.2、pnpm 12.9.1；generation 6 使用镜像内固定 pnpm 8.15.9。
不按项目声明自动下载运行时。源码配置变化失效旧准备；后续检查仍可能因新配置失败。
对于附带独立工具链文档的锁，只在已核验为当前固定 pnpm 后将该文档与项目锁在准备副本分离；
不改项目依赖图、不写回源码，也不启用自动工具管理或下载。

## 选择、证据与兼容

唯一 Maven 根/唯一 Vue/Vite 工程可确定性选择；多个独立入口返回确切目录，停止于模型前。
复用启动请求，增加可选 checkDirectories（最多 8 个目录，每个 200 字符），界面保留原费用确认，提供目录输入。
显式选择仅决定本次检查工程。无自然语言依赖图推断，不扩大 Step 授权。

baselineFailures 与正常 session.checks 分开。evidenceVersion=4 仅把已观察的基线失败带入冻结约束，
基线通过及测试计数不能满足最终检查。基线失败必须由同工程、同目标/runner 的最终 snapshot 重验解决。
v1–v3 保守兼容，不改写历史 Candidate、Run 或 Delivery。工具协议升至 v8，旧镜像在传源前拒绝。

## 影响、替代方案与代价

影响 Application Agent 编排/证据、Infrastructure Workspace/Docker/Python、现有启动 API 和有限表单/诊断文案。
无 Maven 模块依赖、持久化技术、领域状态机或正式交付策略变化。

维持静态拒绝会继续排除安全仓库；只检查可执行文件/脚本声明不能证明项目能力；联网执行项目或开放 shell
突破既有边界；通用构建目标模型、安装器或 CI 引擎超出任务范围，均不采用。
代价是模型前启动时间增加、受限缺失工件恢复仍可能不足、模糊失败会阻塞；收益是实际环境问题在模型消费前暴露。
安全资源保留、外部服务、安装期构建和未知配置的既有限制不通过放宽隔离来解决。

原生行为参考：[Maven dependency-plugin 3.8.1 go-offline](https://maven.apache.org/plugins-archives/maven-dependency-plugin-3.8.1/go-offline-mojo.html)。

当前运行与工程选择说明统一维护于 [LOCAL_SETUP](../LOCAL_SETUP.md#agent-build-preflight)；本 ADR 保留设计理由及当时版本语境。
