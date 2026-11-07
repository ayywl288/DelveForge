# 通用 Agent 命令路径：实现与验证记录（2026-11-07）

状态：**READY FOR USER E2E**。实现、最终构建及本任务所需的可用检查已通过；不代表真实产品业务验收已经完成。

本记录只针对确定性实现验收；付费模型与真实产品 E2E **NOT RUN**。当前分支 `codex/generic-agent-command-path`，从 `089b26f` 开始；没有 push 或 merge，没有启动用户项目或修改业务数据库。

## 交付范围

活动工具仅 `list_files / search_code / read_file / edit_file / exec_command`。语言准入、Maven/前端命令分派、强制专用报告和分阶段安装修复已移除。保留单 Agent、Spring AI、Workspace 隔离、独立 Git 重放、SQLite 证据与原有自动 Delivery；没有增加人工交付确认或累计运行预算。

协议 v9 / 证据 v6。原生命令每次新 shell、显式相对 cwd，环境和 cwd 不跨调用继承；源码与本次项目依赖、缓存保留。非 root、只读根、限制资源、受控工件代理、公开资源真实只读挂载和私有资源不物化。网络、支持环境和保留组件的准确边界见 [ADR-0024](../decisions/0024-generic-agent-command-path.md)。

源码采集采用 Git ignore 加已跟踪/已捕获源码保留；命令修改、删除与重命名进入实际差异。最终必要命令必须与冻结源码一致，失败、超时、取消、缺失与过期不能交付。报告缺失不等于零测试；已有失败用例不能靠删除或跳过消失。失败后的安全部分成果标记为 UNVERIFIED_NON_DELIVERABLE，停止容器后保留，不创建 Candidate。

## 回归替换关系

| 退役内容 | 替代证据 |
| --- | --- |
| EnvironmentReadiness / EnvironmentPreflightDockerTest 的模型前语言检查门槛 | GenericCommandDeliveryTest 的无 Maven/Node 项目真实预检与交付；FullStackAgentSmokeTest / NativeFrontendDeliverySmokeTest / NativeWorkspaceCommandTest 的原生命令能力 |
| DockerDependencyPreparationTest 的独立准备/离线副本/H2 特殊改写规则 | DockerAgentSandboxTest 冷依赖、真实源码变化、最终快照；通用超时/取消；NativeWorkspaceCommandTest 生命周期和网络边界 |
| frontend.py 与定制报告注入、对应适配器专用测试 | 原生 npm/pnpm 测试脚本与锁、workspace 集成；可选 test_reports.py 新鲜报告读取 |
| MavenFullSuiteInputs（无活动调用者） | 通用源码快照，不按语言扩展名判断验证身份 |
| 模型 apply_patch | edit_file；内部 Git 补丁预演、前后像、差异重放及其故障测试保留 |

DockerRetentionRecoveryTest、DockerProxyIsolationTest、ReadToolRecoveryDockerTest、PatchConflictOrchestrationDockerTest、镜像协议与兼容测试已迁移到新接口。历史数据库迁移和 v1–v5 证据读取保留。

## 实测记录

- 最终全量 `mvnw.cmd -q verify`：**1,893 项，1,845 项实际执行，48 项按环境条件跳过，0 failure / 0 error**，全部模块构建和打包通过。包括最后的无效配置清理，覆盖真实 Git、SQLite、Candidate/Delivery 和多步骤状态保护。日志 `target/generic-final-backend-verify.log`，逐套统计 `target/generic-final-verify-summary.json`；统计仅包含本次 183 套报告，排除 target 中历史退役测试的旧 XML。48 项包含按环境开启的 Docker、外部仓库和真实模型测试；本任务相关 Docker 已另行实际执行，真实模型/用户项目测试未执行。
- 前端完整测试 319 / 319（22 文件）；包含命令未执行状态的新回归；`pnpm build`（含 vue-tsc）通过。无 lint 脚本，未虚构该检查。日志 `target/generic-frontend-final.log`、`target/generic-timeline-final.log`、`target/generic-frontend-build.log`。
- Python/Linux 60 项：53 通过，7 项因需要实际镜像暂跳过；随后在最终镜像单独执行这 7 项，7 / 7 通过。日志 `target/generic-python-final.log`、`target/generic-protocol-final.log`。
- 实际生产 Dockerfile 的 JDK 21 / 17 两个镜像均构建成功，并通过启动脚本的协议 v9、工具版本和内容哈希核验。生产镜像 ID 分别为 `sha256:f845da0f8cb663f95e7a98115cc543634fe1873cdc5d1aab40070d0baff629dc`、`sha256:116cad6e4c5aeef0f8494d68cd9efe40c90f4a5a7596b4a4a9090da6a1701939`。C++ 集成另用预装 g++ 的可信镜像；构建和探针没有启动用户应用或改写业务配置。

修复后的定向 `verify`：**164 / 164 通过，0 failure / 0 error / 0 skipped**，日志 `target/generic-verified-integration.log`，逐套统计 `target/generic-verified-integration-summary.json`。覆盖 JDK 17/21 原生命令、未经改写的 Maven Wrapper、协议 v9 与旧镜像拒绝、Maven/npm/pnpm 混合项目完整交付、npm v2/v3 与 pnpm 8/12 独立前端、工作区链接/生命周期/网络边界，以及凭据和命令事实回归。

此外，`target/generic-final-integration.log` 已实际通过 C++ 通用交付和命令边界 2 项（包括无报告验证、超时、截断、取消与部分成果）、保留/恢复 4 项、公开/私有资源与失败用例不可跳过/删除 3 项、Java release 8 兼容、冷依赖代理、读取恢复和编辑冲突恢复。C++ 场景从失败检查修复后经正常自动 Delivery，使当前 Step 为 SUCCEEDED；仍有待执行 Step 时 Plan 保持 ACTIVE。

记录的修复前集成批次为 71 项：66 通过、4 失败、1 项旧镜像条件跳过；保留原始统计 `target/generic-first-integration-summary.json`，未将失败记为通过。修复后覆盖的实际问题包括：Maven Wrapper 空凭据初始化被误判为私有源码、pnpm 12 并发元数据造成代理 OOM、独立前端测试锁缺少 pnpm 12 原生环境锁信息，以及旧 apply_patch 断言未随 edit_file 的准确冲突分类迁移。生命周期标记检查改为检查真实 changedPaths，避免将 package.json 中的脚本文字当作新增文件；仍断言安装未产生标记文件。测试锁由固定 pnpm 原生生成，项目依赖图和完整性值未变，产品不转换用户锁文件。新增 Shell 反例回归同时验证非空凭据、拼接、默认值和已知令牌仍不可进入执行视图。

集成测试结束后已确认测试所有者没有遗留容器。

已执行的浏览器检查使用纯合成记录，没有连接业务后端。按 impeccable polish 与 [Vercel Web Design Guidelines](https://github.com/vercel-labs/agent-skills/blob/main/skills/web-design-guidelines/SKILL.md) 复核了本次受影响组件。桌面、768px 和 390px 检查了当前验证、最近优先的失败/重试历史、未执行状态、展开输出、未知测试数和长命令换行；无横向溢出，键盘展开/输出滚动及可见焦点正常，控制台无 error。截图：`target/generic-command-ui.png`。

## 用户执行一次真实授权 Step

1. 使用本分支代码，停止旧版前后端，在仓库根目录运行 `./setup-delveforge.ps1`。启动器会按当前源码构建并核验协议 v9 的 JDK 17/21 镜像；已有 API Key 仅按启动器现有配置管理，不输入到执行容器。默认产品镜像没有 Go/C++ 编译器，需要它们时由用户提供相同协议、预装工具链的可信镜像。
2. 打开 `http://127.0.0.1:5173`，进入已有项目的演化页面，选择一个已授权方向下范围明确、仍未执行的 Step；核对目标、原资产权限、隔离 Working Copy 和可检查的验收要求，依次点击“确认此步骤”“开始 Agent”，在已有费用确认框中勾选预算说明后点击“确认费用并开始”。不要复用上一 Step 的授权。
3. 观察工具循环是否读取该仓库 README、指引、清单、锁和 CI，使用它们规定的原生命令。普通参数/编辑/编译失败应能在同一 Run 纠正；改动后的必要检查应重新运行。界面“当前源码验证”与展开历史不能混淆。
4. 正常成功应自动冻结 Candidate 并进入原有 Delivery，交付后当前 Step 为 SUCCEEDED，正式 Working Copy 与 Candidate 树一致，原资产不变。若还有未执行 Step，Plan 必须保持 ACTIVE；不要要求此时整个 Plan COMPLETED。
5. 在交付后的软件上执行该 Step 的业务验收，核对实际功能和交互。保存 Run/Candidate/Delivery 标识、真实命令与退出结果、交付 revision、Step/Plan 状态和业务验收结果。只有这一步完成，才能另行评价真实模型的产品效果。
6. 若检查仍失败或被取消，确认没有可交付 Candidate；安全捕获成功时在配置的 Agent root / Run 目录查看 `partial.diff`、`partial-metadata.json`。无法安全捕获会明确报告，旧 Run 不提供恢复执行。

## 限制

- 确定性模型替身证明工具循环、真实执行、冻结与交付机制，不能替代付费模型和产品业务验收。
- 默认工具链是 JDK 17/21、Node 22.16.0、npm 10.9.2、pnpm 12.9.1 / 8.15.9；未提供任意系统安装或所有仓库支持。
- 单次命令默认上限 180 秒，可通过既有 `delveforge.agent.limits.check-seconds` 配置调整。此次 pnpm 混合场景的原生完整性拒绝约 173 秒、修复后安装约 163 秒，均没有超时；大型依赖图仍受单次限制。
- 仅允许 ADR 列明的公共工件网络；外部服务、私有仓库、凭据和其他下载站点需要明确环境支持，失败不能被声称为验证通过。
- 平台保留执行事实和必要命令范围，无法通用证明任意脚本的业务含义。无可选报告的工具链没有通用逐用例失败等价判断。
- `mvnw clean verify` 的 clean 在旧的、受保护的 target 现场上失败，随后使用 `mvnw verify`；只清除了本次退役类/资源的生成产物，没有绕过验证或重置旧现场权限。
