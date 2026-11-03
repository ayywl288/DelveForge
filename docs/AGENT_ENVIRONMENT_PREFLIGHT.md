# Coding Agent 环境预检

当前任务把安全 Git 准入、检查环境就绪和源码/测试结果分开，设计见 [ADR-0022](decisions/0022-native-build-environment-preflight.md)。

已确认 Step → 安全沙箱准备 → 原生依赖准备和实际离线基线探针 → 模型循环 → 当前源码检查 → Candidate/Delivery。
预检沿用 PREPARING，不创建新的领域生命周期。预检阻塞的 Run 模型请求数、modelCalls 和 tokens 均为 0。
预检不是最终源码通过证明，也不保证后续修改依赖配置后仍可构建。

| 结果 | 含义 | 进入模型循环 |
| --- | --- | --- |
| READY | 实际检查路径已执行，必要能力有观察证据 | 是 |
| READY_BASELINE_FAILURE | 实际编译/测试执行后出现明确源码或测试失败 | 是，必须修复并重验 |
| ENVIRONMENT_BLOCKED | 工具、依赖、外部前提或所需证明不足 | 否 |
| SECURITY_BLOCKED | 可信源码或沙箱边界不能成立 | 否 |
| SELECTION_REQUIRED | 多个独立工程，尚未明确检查入口 | 否 |

Maven 编译失败不证明测试 runner 已运行；输出记录 observedPhase、unprovenCapabilities。
前端 Node/Vitest 报告继续独立归一化；只执行 shell/声明脚本或零退出不能单独证明就绪。
没有 test 脚本显示 NOT_DECLARED，不制造测试、测试通过或执行失败。

## 工程选择

唯一入口可自动选择；一个普通 Maven + Vue/Vite 组合可同时预检。大型仓库有多个独立入口时，先返回目录再开始模型。
只检查本 Step 相关工程时，在原启动请求中明确指定：

```json
{
  "paidUsageAcknowledged": true,
  "targetJdk": 21,
  "checkDirectories": ["server", "apps/web"]
}
```

最多 8 个目录，每个 200 字符；必须来自实际发现的工程，仓库根为 `.`。此输入不增加任何源码修改权限。
界面在现有运行配置中提供同一输入。预检事件显示候选目录、真实阶段、缺失证明及有界诊断。
每次重新启动仍要求原费用确认，预检不会替用户确认 Step。

## 当前受控范围

- 安全准入不再以 Maven 根数、profile、锁文件代数/缺失或脚本声明作为整体拒绝条件。
- Maven Reactor 保持原生；普通 profile 由实际 Maven 求值。使用已有受控 JDK 17/21，不自动下载其他 JDK。
- npm 使用固定 10.9.2，对 v2/v3 package-lock 或 shrinkwrap 使用原生 ci；无锁在私有准备副本原生 install。
- pnpm 使用固定 12.9.1 处理 generation 9，固定 8.15.9 处理 generation 6；普通 workspace 的关联交给 pnpm。
- 已核验为当前固定 pnpm 的独立工具链文档只在准备副本中分离；项目锁和依赖关系保留，自动工具下载仍关闭。
- 无锁解析按 Run 冻结，记录 resolutionSha256 和 RUN_FROZEN_RESOLUTION；不把生成锁写回权威源码。
- 支持明确校验的 npmjs、npmmirror、npm.taobao、yarnpkg 公共 registry 地址映射。实际下载始终通过固定 HTTPS 上游代理。
- 安装与执行分离，安装脚本始终关闭；实际项目脚本无网络，不提供任意 shell 工具。

配置摘要覆盖 manifests、锁、workspace 和受控 npm 配置。变更使准备失效；后续调用使用新配置原生准备，失败准确返回。
同一 Run 相同安装输入可复用准备结果，项目执行不能修改准备卷。

Maven 项目代码和离线 go-offline 不获得工件网络。缺失工件仅按实际诊断中的受限身份，由固定空 POM 的
dependency:get 获取。最多 8 轮、32 个身份，耗尽或不能证明身份就阻塞，不回退到联网生命周期。

## 边界与历史

原 Git 身份、A/P 来源、资源能力/秘密保护、执行投影、源码 snapshot 和正式 Delivery 守卫保持。
被保留的可执行源码/测试仍不能靠缺失后剩余测试制造 PASS；此安全限制可能使某些安全保留的仓库无法执行。
私有 registry、凭据、安装期原生构建、未知 pnpm 设置/catalog/hooks、自定义运行时、外部服务仍可能不可用。
未知非零退出保守阻塞，不承诺任意仓库或任意测试框架就绪。

协议 v8 要求从当前 Dockerfile 重新构建镜像。evidenceVersion=4 增加 baselineFailures；普通 checks 不包含基线探针。
仅失败事实进入最终化约束，基线成功不计入最终通过/测试数。v1–v3 继续按原规则读取，无迁移或历史改写。
实际执行、跳过、公开仓库样本与剩余问题见 [本任务验证报告](validation/native-build-preflight-20261103.md)，声明支持不等于已执行通过。
