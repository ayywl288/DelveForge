# M5-F1b 暂停恢复点与 Worktree 迁移

日期：2026-10-16（Asia/Shanghai）。这是明确标记的 **WIP／PAUSED**，不是 F1b 验收报告，
不代表隔离方案通过，不授权业务 Recovery。按用户指示暂停实验，优先迁移实验 Worktree。

## Git 与目录

- 暂停前分支：`feature/m5-lab`。
- 暂停前 HEAD：`56e942436f4bd7c2e79f13db19654fcf1fc9ca2e`。
- 源 Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。
- 目标 Worktree：`E:\develop\DelveForge-worktrees\m5-lab`。
- 原始 main：`E:\develop\DelveForge`，HEAD
  `474375d8f9f2dcd822037fa85c5e197198c4e5cf`。原有六张未跟踪 UI 图片不加入提交、不清理。
- 仅提交 F1b 未完成原型、原始失败结果和本恢复点；不改写 Git 历史，不 Push／合并／移动 Tag。
  本次 WIP SHA 以 Git 日志及迁移交付消息为准。

## F1b 已完成的事实

1. 读取了当前领域／架构／Roadmap／ADR-0001、F1 报告和原型，核对了本机权限。
   Windows Sandbox 启动器不存在；虚拟化服务运行不等于已有可用隔离 VM。
2. 在新建临时目录实际尝试 CreateRestrictedToken 与 CreateProcessAsUserW；API 返回成功。
   该 Token 是当前用户的受限版本，不是独立系统账户；没有创建账户、修改现有账户或 VM 配置。
3. 已保存未完成的 `isolation_spike.py` 与预检 `token_probe.py`。当前原型使用 F1 原生进程
   辅助函数；只修改本任务新 Fixture 的 ACL，没有修改真实 Workspace、生产 ACL 或业务数据。
4. 两轮实验各 **3 个场景中 1 个通过、2 个失败**，未到达的阶段没有冒充验证成功。
   第二轮真实确认受限进程在目标阶段之前退出，退出码 `0xc0000142`；WMI、受限 Token 的
   真实目标写入及完整 staging 接收仍未验证，不能由 CreateProcess 返回成功推断已运行。
5. 已真实复现 ACL 撤权不能撤销先前打开的写句柄：第二轮新打开文件返回 error=5，已有句柄
   约 200ms 内将新建 Fixture 文件从 20 写到 38 字节。自有 Owner 被强制终止后已知 writer
   退出；第二进程竞争观察已保存，但这不是完整隔离／安全接管证明。

## 保存的原始资料

- 源码：`tools/spikes/m5-f1b/isolation_spike.py`、`token_probe.py`。
- 原始结果：`tools/spikes/m5-f1b/evidence/initial-results.json` 与 `pre-pause-results.json`。
- 完整 E 盘归档：
  `E:\develop\DelveForge-worktrees\m5-lab-artifacts\migration-20261016`。
- 归档包含两个实验根的 80 个普通文件、原始目录／文件安全描述符、6 个 reparse point 的
  目标及类型元数据、预检目录和开发源码。链接元数据保存在 manifest，未在归档中建立指向
  C 盘的活跃可写别名；没有跟随链接读取其他目录。普通文件复制后 SHA-256 已核对。
- C 盘原始 Fixture 与源码保留，没有清理；历史 config／日志中的 C 盘路径不批量改写。
- 原有未跟踪 `tools/spikes/m5-f1/__pycache__/ownership_spike.cpython-311.pyc` 原样保留，
  不提交生成的字节码，也不通过 clean/reset 清理。

## 尚未完成／确认后才可恢复

- 定位 `0xc0000142`，确认是否涉及受限进程的窗口站／桌面或其他启动权限；未实施任何
  桌面／窗口站修复，不能通过给现有系统对象扩大权限来绕过用户约束。
- 实测受管执行与 WMI broker writer 的真实身份、权限、Protected Workspace 写入能力，
  Owner／Job 退出后的持续写入、ACL 时序、路径别名／符号链接及 staging 接收边界。
- 尚无独立身份／VM 隔离保证，未形成 F1b 最终技术 Gate 或集成许可，Recovery 继续阻塞。
- `token_probe.py` 是原始预检快照，内含旧 C 盘 F1 helper 的绝对路径；恢复前应先修正该路径。
  `isolation_spike.py` 默认按相邻目录定位 F1 helper，随 Worktree 移动；历史 JSON 的路径仅作证据。
- 未来新 Fixture、控制台日志、构建临时根及 Python 缓存应放在
  `E:\develop\DelveForge-worktrees\m5-lab-artifacts` 下的新目录；在任务进程内设置 TEMP／TMP／
  PYTHONPYCACHEPREFIX，不修改系统环境。不要复用旧实验目录继续运行。
- Codex 的会话／项目绑定不会因 Git move／repair 自动获得验证。建议在新 E 盘目录开启或
  重新绑定会话后继续；当前阶段仅完成保存与迁移，等待用户确认，不自动恢复 F1b。
