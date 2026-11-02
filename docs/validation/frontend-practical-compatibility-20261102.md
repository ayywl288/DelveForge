# 普通 Vue 仓库兼容性与真实模型试验

日期：2026-11-02。分支：`codex/full-stack-evolution-checks`。

## 试验前边界

先完成通用兼容性修正和确定性验证，提交固定实现基线，再启动一次真实 DeepSeek 产品流程。
真实试验开始后不修改 DelveForge 实现、提示或规则，不改原仓库，不代写目标功能，不重写生成的方向或计划。
技术检查、Candidate/Delivery、独立功能验收分别记录。失败保留，不换仓库追求成功。
用户已明确授权本次 API 使用并另行提供凭据；凭据仅进入隔离进程环境，不进入本报告或 Git。

## 兼容性抽查

主样本为三个持续维护的普通公开项目；仅读取公开文件，并对两个原样 Git 副本调用真实兼容性适配器。
没有运行样本安装脚本，也没有修改它们的源文件、manifest 或锁文件。

| 项目 | 观察 | 分类及处理 |
| --- | --- | --- |
| [v3-admin-vite](https://github.com/un-pany/v3-admin-vite/tree/c2c895d71d571e9c18c2453fa85469a479c79fc6) | `vue-tsc && vite build`、`test: vitest`；锁为 v9/SHA-512。固定 tree 含环境文件、`.npmrc` 及符号链接 | 常见脚本被拒绝是人为限制，已通用修正。受保护路径/符号链接仍使实际预检 BLOCKED，不清理或绕过 |
| [IT-Tools](https://github.com/CorentinTh/it-tools/tree/d505845f918e946ec300af7b36efc107e2f66e9e) | pnpm 9.11.0、v9 锁；工程预检 COMPATIBLE；`concat-map@0.0.1` 锁记录为 SHA-1；构建含 NODE_OPTIONS，test 转调 test:unit | 精确版本匹配是人为限制，已修正；SHA-512 限制及未识别复合脚本保留，不能把预检通过说成安装/构建通过 |
| [Vue Pure Admin](https://github.com/pure-admin/vue-pure-admin) | 缺 Vitest，Node 要求 >=22.22.1，工作区配置与复合构建 | Vitest 不是工程准入前提，已修正；当前固定 Node 22.16.0、工作区及自定义构建不支持，延期 |

另对 mini-qr、vue3-ts-vite-starter-template 的根元数据作补充筛选：前者含环境文件/`.npmrc`，后者含工作区配置。
没有进入真实模型流程，也没有为它们加适配。未因寻找演示成功而外部改造项目或无限增加支持范围。

必须保留的安全边界包括固定受控下载入口、锁完整性、禁用安装生命周期/hook、离线检查、源码快照及不可变 Candidate。
当前对全部环境文件、符号链接、工作区的拒绝是现有保守支持边界，不表示这些公开项目本身不安全。
放宽它们需要独立评估，不在本次有限稳定化中实现。

## 通用修正

- 工程准入只要求 Vue 3/Vite/TypeScript；vue-tsc、Vitest 随对应检查选择，不强迫项目添加工具。
- `packageManager` 与唯一锁对应，接受 npm 9/10、pnpm 9–12 的明确版本及 Corepack hash 后缀；实际镜像仍固定 Node/npm/pnpm。
  联网准备不携带此字段，不能自动下载或切换仓库指定工具链。
- 增加明确列举的 `vue-tsc` 构建组合、`vitest` 单次运行，以及 `type-check` / `test:unit` 名称。
  不提供通用脚本解释器、环境赋值、任意转调或 shell。
- 缺少可选检查返回 `CHECK_NOT_AVAILABLE`，不伪造 PASS；NOT_TESTED 合法。
  已声明但未安装、执行失败和配置不支持仍独立分类；不可用记录不能清除已有失败。

## 固定真实试验设计

试验前确定性验证：`mvnw.cmd -q verify` 退出 0，1819 项、零失败/错误、40 项按条件跳过
（domain 209、application 858、infrastructure 530、app 222）。单独启用 Docker 的 npm/pnpm
全栈 smoke 为 2 项、零失败/错误/跳过，真实执行耗时 583.412 秒；不是实际模型试验。
前端 304 项测试通过，typecheck/build 通过。最终 Linux 镜像策略 46 项及代理 4 项通过。
最终固定镜像的原生协议 7 项通过，本地最终 package 退出 0。
宿主 Python 缺少 YAML 依赖的初次尝试保留日志，成功策略运行使用正式镜像的 Python/YAML 环境。
smoke 后补充了非法元数据/脚本不得伪装为不可用检查的负向拒绝规则，该规则经最终 46 项策略回归验证，
没有把负向规则补充描述为重新执行整个 smoke。

实际试验镜像：JDK 21 `sha256:b4a12ae2fc98d198ac624062c2efc93459b46913ca3854d392acadc26e00b97c`，
JDK 17 `sha256:2e321fb37187a992779be7bc5e2585cfb99abde457ce4538ca7135adff31999b`。
均由正式启动辅助流程核对当前脚本和 Node 22.16.0 / npm 10.9.2 / pnpm 12.9.1。

唯一目标仓库：`https://github.com/CorentinTh/it-tools.git`，GNU GPL v3，固定 revision
`d505845f918e946ec300af7b36efc107e2f66e9e`。只在本机隔离试验中使用，不发布目标工程产物。
这是工程预检通过的普通 Vue/Vite/TypeScript 单工程，安装和脚本限制已知，不能预先宣称端到端受支持。
保留 SHA-1 旧依赖及原脚本，真实试验可能在检查阶段失败；不因此更换项目或放松锁完整性。

在观察任何模型结果前固定用户目标：在现有 JSON 查看/格式化工具中，支持按对象路径和数组下标提取字段值，
可复制提取结果；非法 JSON、非法路径、路径不存在及空输入有明确反馈；保持原格式化、排序和缩进能力。
内容只在浏览器本地处理，不增加后端或上传，不扩大为整站重构。方向和具体 Step 必须由 DelveForge 正常生成与确认。

实现基线、真实模型活动、交付结果及独立验收将在真实运行后据实追加；当前设计记录不构成成功证据。
