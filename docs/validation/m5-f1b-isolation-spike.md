# M5-F1b — Protected Workspace Isolation Spike

日期：2026-10-16（Asia/Shanghai）。分支 `feature/m5-lab`，恢复基线／WIP
`19b80673b877cd3327d382c00a58510b38f9d286`。
Worktree：`E:\develop\DelveForge-worktrees\m5-lab`。

**最终 Gate：NO-GO。当前候选不能封闭潜在写入者集合，不得接入业务 Recovery。**
本轮确认了原受限进程失败的具体初始化阶段，并复现 ACL、WMI 逃逸与 staging 接收的缺口。
独立系统账户／VM 隔离未建立，不能将同 SID 的受限 Token 称为独立身份。

最终隔离实验 **7 个场景中 5 个观察断言成立、2 个未到达目标阶段**；原生退出码 **1**。
这里的 5 个包含反例复现，绝非“隔离验证 PASS”。没有删除失败断言、将未运行的动作计为成功，
也没有把外层检查预期退出码的 PowerShell exit=0 当成实验通过。

## 1. 契约、恢复点和修改范围

已核对 AGENTS、PRODUCT、ARCHITECTURE §2/§8.3、DOMAIN_MODEL §8.15 及失败／验证不变量、
ROADMAP M5、ADR-0001、F1 报告、WIP 原型／失败证据和迁移恢复点。
生产执行仍通过 Workspace Port，WorkspaceCommandRunner 的 descendants 清理不是 OS 沙箱；
ExecuteEvolutionStepUseCase 的检查点及 SQLite CAS 不能阻止旧 writer 继续修改文件。
本轮不修改上述 Java 实现、生产 Domain/Application/API/SQLite、状态机或依赖。

继续保留既定边界：只考虑未来双 RECOVERY_REQUIRED；必须证明旧写者不能继续写，才可考虑
独立 Recovery 授权。EXECUTING／VERIFYING／ROLLING_BACK 遗留 UNKNOWN 不接管、不重放、不改判。
旧历史没有隔离事实，不能因为本轮原型存在就补造停止证明。

本轮修改仅限 `tools/spikes/m5-f1b` 和本报告：

- `token_probe.py`：移除硬编码旧 C 盘 helper 路径，按相邻文件定位；五组真实初始化对照。
- `private_desktop.py`：只新建随机名私有 desktop，借用当前 station，不调整其 ACL，不切换用户可见桌面。
- `loader_probe.py`：仅调试自建 suspended child，在该 child 的内存启用 loader snaps，记录原生加载器事件。
- `isolation_spike.py`：显式要求新的 E 盘输出父目录；保留原受限失败测试，加入明确标注的宿主对照、
  WMI／预开句柄反例和 staging 父目录别名反例。子进程使用 `-B`，临时根和 PowerShell 缓存指向任务目录。
- 新增 `evidence/resumed-*` 与两份早期 station 探索结果；原 `initial-results.json`、
  `pre-pause-results.json`、F1 原型和原始迁移归档均未覆盖。

## 2. 0xc0000142：实际定位与未定位的部分

本机 Windows build 22631、Python 3.11.3 x64。正式原生实验在调用者普通用户 Token 下运行，
不是新增管理员身份。`CreateRestrictedToken(DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED)` 和
CreateProcessAsUserW 均成功。实际 debug child Token 查询为：同宿主用户 SID、restricting SID
`S-1-5-12`、restricted=true、Medium integrity、TokenPrivilegeCount=1；宿主为 restricted=false、
TokenPrivilegeCount=5。计数不表示五项权限全部 enabled。

[五组初始化原始结果](../../tools/spikes/m5-f1b/evidence/resumed-init-results.json)：

| 真实对照 | 实际退出／用户代码 |
| --- | --- |
| 普通 Token、继承 desktop | 0，进入 Python 用户代码 |
| 原受限 Token、继承 desktop | 0xc0000142，未进入 |
| 原受限 Token、新建私有 desktop | 0xc0000142，未进入 |
| 附加当前 Logon SID 的受限候选、继承 desktop | 0xc0000142，未进入 |
| 附加 Logon SID、新建私有 desktop | 0xc0000142，未进入 |

Logon SID 仅作为另列的兼容性对照；原隔离场景仍使用原始严格 Token，没有将该候选替换成更弱的安全结论。

[实际受限进程加载器事件](../../tools/spikes/m5-f1b/evidence/resumed-loader-restricted-trace.json)
明确记录：KERNELBASE.dll 的 init routine 在 DLL_PROCESS_ATTACH 失败，随后加载 Windows subsystem
KERNEL32.DLL 返回 0xc0000142，进程初始化终止。普通 debug control 正常进入 Python 并 exit=0。
因此已定位在 **Windows 子系统 DLL 初始化、Python 用户代码之前**，不是 actor 的 ACL／WMI 断言失败。
微软将该状态定义为 [STATUS_DLL_INIT_FAILED](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-erref/596a1078-e883-4972-9bbc-49e60bebca55)。

**尚未定位** KERNELBASE 内部哪一次资源／权限检查失败，不能断言只是 desktop 或某个文件 DLL 缺失。
私有桌面未解决问题，进一步削弱了“只需给现有 desktop 加权限”的解释。没有为此改生产 ACL。
诊断使用测试过的 x64 PEB NtGlobalFlag 偏移，仅写本任务 child 内存，不设置全局 GFlags 或注册表；
它是版本相关的诊断原型，不能作为生产运行依赖。

两条较早探索也保留原始失败：随机具名 station 创建 error=5；自动命名 station 的 CREATE_ONLY
返回 error=183。前者与[具名窗口站的权限约束](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createwindowstationw)
一致；后者拒绝复用已有 station。均未改既有 station 的 DACL。最终只创建新 desktop，结束后关闭自有句柄。
[Restricted Token 官方说明](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-createrestrictedtoken)
也没有被拿来替代实际子进程到达目标阶段的证据。

## 3. 最终真实进程实验

[完整原始结果](../../tools/spikes/m5-f1b/evidence/resumed-isolation-results.json)。
每个场景创建全新的 Fixture，Owner／竞争者／writer 均为独立 OS 进程，崩溃使用自有 Popen.kill
（Windows TerminateProcess），不是线程模拟。未运行任意仓库构建。

| 场景 | 实际结果与边界 |
| --- | --- |
| 01 restricted + broker | 失败／未到阶段：0xc0000142，受限 actor 未执行，不能声称受限 WMI 已被阻止或已逃逸 |
| 02 已有写句柄撤权 | 新 open error=5；原句柄仍从 19 写到 37 字节。已知受管 writer 随 Owner 崩溃退出；竞争者此前 258，之后取得 gate |
| 03 restricted staging writer | 失败／未到阶段：0xc0000142；受限 staging 写入能力未验证 |
| 04 宿主 staging 对照 | 明确为普通 Token writer。正常字节可读，穿越／绝对路径／ADS 被拒；多硬链接被拒；活跃 writer 触发 error=32，退出后可读；不信任 Verification 字段 |
| 05 同 SID WMI + ACL | WMI ReturnValue=0；writer 实际不属于 Job，同宿主 SID、非受限 Token。Owner 死亡及 Job active=0 后从 34 写到 57；恢复宿主写权限后又从 66 写到 89 |
| 06 WMI 预开句柄 + 撤权 + 崩溃 | 新 open error=5，Owner 已退出，Job active=0，WMI writer 句柄仍 WAIT_TIMEOUT=258，文件从 32 写到 56；第二实例已取得 gate |
| 07 staging 父目录 junction | 原 snapshot 叶文件检查仍接收 41 字节；父目录 alias 指向本场景新建 protected 目录。这是原型接收边界反例，不是可上线接收器 |

05/06 的 broker writer 身份从实际进程 Token 查询，CIM 返回 PID 与本次 actor 的 PID 文件匹配；
IsProcessInJob 是对当前任务 Job 的真实查询。自有 keeper Job 句柄仅为明确终止／查询所保留，
不隐藏成生产机制；显式 TerminateJobObject 后核对 active=0。文件增长用实际 read_bytes 长度衡量。

05 还实测原路径、`..`、扩展路径前缀、大小写变体、硬链接和 junction 六种访问：同 SID 可写时均可写；
撤销新 open 权限后六种均 error=5，短观察窗口内普通反复 open writer 暂停写入。此时宿主的新 open
也被拒；一旦恢复宿主所需权限，仍存活的 broker 立即继续写。这不能隔离可信宿主与同 SID 逃逸者。
06 进一步证明“先撤权再恢复接管”的方案还会遗漏已有 granted handle。

符号链接创建实际 error=1314，因此 **未验证符号链接绕过／拒绝**；没有以 junction 或 mock 代替该项。
几个 200～250ms 的窗口是反例观测，不是长期不写入的证明。

竞争者取得 Mutex 只是竞争观察；`recovery_eligible=false` 记录本轮 NO-GO，**不代表已实现资格判定器**。
没有授予或执行 Recovery 写入。进程结束扫描只用于本次实验收尾，不能反向成为业务 StopProof。

## 4. 可以确认、不能确认及 staging 接收建议

本机可确认：当前受限进程初始化失败；只撤销 ACL 不能撤销已有写句柄；已验证 WMI 路径可留下 Job
外的同 SID writer；Mutex 释放与 Job 归零均不足以封闭写入者；当前字节接收原型还存在父目录别名缺口。

未建立：独立执行账户身份、其 broker 身份传播／权限保证、完整沙箱、无宿主写入挂载的 VM、任意服务／
COM 代创建边界、任意外部写者隔离、跨登录会话、符号链接、OS 崩溃／断电后的保证。
WindowsSandbox.exe 实际不存在；没有因此假设所有其他 VM 技术都不可用。未创建或改变真实系统账户，
未更改现有 VM 配置，没有将管理员权限需求绕过成模拟验证。

若后续改用无宿主 WorkingCopy 写入挂载的隔离 staging，宿主只能将输出视为**不可信字节提案**。
建议在宿主可信目录中持有稳定目录／文件身份、拒绝不明 reparse 链和多硬链接、限制大小／类型／路径，
建立不可被旧 writer 继续修改的宿主副本，再执行现有 scope、Git 属性、精确 revision 和独立全量验证。
这些是下一候选的要求，**本轮未实现完整接收协议**。不能直接信任 sandbox 的成功 JSON、Verification
结果、Candidate／baseline 声明、脚本、Git config/hooks、时间戳或原目录名；不能直接把 staging 当可信 WorkingCopy。

## 5. 部署成本、后续价值与 Decision Required

当前受限 Token + 同 SID ACL 候选 **不值得直接集成**：连受限 actor 初始化都未完成，现有宿主对照又
给出了已存活写者反例。没有据此添加生产 Port、SQLite 字段、JNI 或任务引擎。

若继续探索独立低权限身份，实际部署至少涉及执行凭据／独立 SID 管理、目录所有权与父目录 ACL、
原子启动／句柄继承、服务代创建身份、隔离切换和已有句柄处理；当前本地用户 Token 的轻量方案未承担这些成本。
若选择新 Sandbox／VM，则需要受支持 OS 功能／资源、镜像与工具链维护、明确禁用宿主 WorkingCopy 写挂载、
受控导出／接收及独立宿主验证。以上为成本分析，没有部署或性能实测，不承诺资源数字。

值得继续的仅是**另经批准的隔离技术 Spike**；下一 Slice 最小边界仍是封闭写入集合的 OS 实证，
不能直接转 F2/F3。先让原 F1 的 broker writer 在 Owner 退出后确实无法写受保护资源，再讨论集成 ADR／Port。

最小 Decision Required：

1. 是否允许在另行准备的环境提供独立执行身份或无宿主写挂载的 Sandbox／VM，以及选择哪种受支持部署边界。
   本轮不代用户创建系统账户、启用功能或配置既有 VM。
2. 若不承担该隔离成本，Recovery 应继续阻塞；不能用人工勾选、超时、PID 消失或 CAS 替代停止／隔离证明。
   是否继续研究新的技术候选由用户决定，本轮不自动开始。

既定的原始 baseline、独立 Recovery 授权、旧无所有权历史及遗留 UNKNOWN 阻塞不再重新裁定；
本轮不会将历史旧执行追溯标记成受新机制保护。

## 6. 实际命令、证据与交付检查

在指定 E 盘 Worktree 中，先创建新的任务专有输出父目录。本轮父目录：
`E:\develop\DelveForge-worktrees\m5-lab-artifacts\f1b-resume-20261016`。
以下 CLI 每次另建随机 Fixture，不采纳原 WorkingCopy。仅为当前进程设置 TEMP／TMP、
PYTHONDONTWRITEBYTECODE 和 PSModuleAnalysisCachePath 到该任务目录；不修改系统环境。

```powershell
python tools/spikes/m5-f1b/token_probe.py --output-parent $taskOutput
# 本轮 native exit=1，五组对照中只有普通 Token 进入用户代码
python tools/spikes/m5-f1b/loader_probe.py --output-parent $taskOutput
# 本轮 native exit=0，完成两个真实 debug child 的诊断；不是隔离通过
python tools/spikes/m5-f1b/isolation_spike.py run --output-parent $taskOutput
# 本轮 native exit=1，7 场景：5 个观察成立，2 个初始化失败
```

本轮实际 shell 已设置 PYTHONDONTWRITEBYTECODE=1；子进程命令也显式使用 -B。
[实际退出码](../../tools/spikes/m5-f1b/evidence/resumed-native-exits.json) 区分 native 失败与外层记录完成。
最终结果根分别为 `init-probe-tdlfg2zn`、`loader-trace-lb5qhtgk`、`delveforge-m5f1b-cpzfv41y`。
完整日志位于该父目录的 `final-init-console.log`、`final-loader-console.log`、`final-isolation-console.log`；
完整控制组和受限组 loader 日志保留在各自 trace 根。最终执行源码快照保存在父目录 `final-source`。

最终三份结果 JSON 的 source_files 同时记录实际字节 hash 和 LF 规范化 hash；仓库 Git 使用 LF，复审需区分 CRLF。
较早的两轮迁移前失败与本轮前期探索不计入上述最终场景数量；均保留原始资料，未覆盖或累计冒充全量成功。

本轮没有生产后端／前端变更，**未运行 Maven verify、前端 test/typecheck/build**；历史 M0～M5-E 的
独立审查 PASS 不被列为本轮重跑或当前隔离证明。提交只包含本报告、原型和新证据，Commit SHA 见 Git
与最终交付消息。收尾检查包括语法编译、原型／证据 hash、原始 main／Tag／用户未跟踪文件一致性、
本任务残留进程检查、Git diff 检查；检查记录与原始结果分开保存。

恢复前即存在的 F1 bytecode 未跟踪文件原样保留，不加入提交、不清理。没有 Push／合并，完成后停止等待独立审查。
