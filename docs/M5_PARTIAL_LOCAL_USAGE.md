# M5 PARTIAL 本地使用与数据边界

本文适用于包含 M5-A～E 与 V21 migration 的候选版本。M5 为 **PARTIAL**，Recovery 写入正式延期；本地合并、发布和真实用户数据库升级各需各自的验收与授权。最终验收记录见 [M5 Final Acceptance](validation/m5-final-acceptance-20261017.md)。历史 M4／各 Slice 报告只证明其各自版本及当时的验证范围。

## 已实现的有限失败处理

- **Verification Failure：** 明确完成的负向验证保存为 `VERIFICATION_FAILED`，保留 Candidate，`lastVerifiedRevision` 不前移，后续 Step 阻塞。超时、进程崩溃或证据不足不自动解释为明确 FAIL。
- **Repair：** 用户对当前失败 Candidate 单独授权；保留原 Step baseline 和此前失败历史，在同一 Step 内修改后重新验证全部标准。再次 FAIL 仍不能推进信任；只有验证 PASS 才提升可信 revision。
- **User Rollback：** 用户独立授权，将当前失败 Step 恢复到它首次执行的 baseline。只有精确恢复被确认后才进入 `FAILED`；它不把失败 Step 标为成功。
- **Automatic Rollback：** 仅处理正常调用内已确认的受控 Execution Failure，先保存失败诊断，再尝试精确恢复。恢复确认后为 `FAILED`，恢复失败或不能确认则阻塞；未知执行结果不触发猜测性自动回滚。
- **Retry：** 仅允许已精确恢复的 `FAILED`，需要新的独立持久化授权；归档此前失败与授权快照后回到 `READY`。Retry 不调用 Provider 或修改代码；之后仍需用户显式 Execute，且执行绑定本轮 Retry 身份。

首次 Step 确认、Repair、User Rollback 与 Retry 是不同的授权。一次授权不能替代另一种动作。Project Deletion 只隐藏导航、保留业务历史和 Git 副本；已授权、未决、损坏或不能证明安全的记录继续拒绝删除。

## 长期阻塞与浏览器等待标记

本版不提供 Recovery Port／UseCase／API／UI，不提供启动接管、后台补偿、重放、自动解锁或手工“确认旧写者已停止”的替代入口。

`RECOVERY_REQUIRED` 与遗留 `EXECUTING`、`VERIFYING`、`ROLLING_BACK` 未决检查点继续保留事实并阻塞。刷新、关闭标签页、重启后端、超时、PID 消失或普通锁释放都不保证解除阻塞；也不能由此推断原写者已经失去写能力。保留数据库、WorkingCopy 和日志，停止对该现场发起新的写操作。

同一 origin 的 `delveforge.execution.v1` 是客户端未决请求标记，不是数据库身份或任务取消凭据。断网后只查询，不自动重发 Execute／Repair／Rollback／Retry。未知、损坏或不可读标记不可为了继续操作而清除；只有匹配当前请求及授权身份的已采纳终态证据才能正常解除。Web Locks 不可用或存储不可读时写操作失败关闭。

升级前关闭旧版页面并确认请求状态。新旧前端不要同时连接同一个可写环境；切库不能靠清除站点数据取消原请求。独立环境使用不同端口或浏览器配置文件，保留旧 origin 的未决标记与原现场，不能借新页面绕过旧环境的保护。

## 当前 Git 输入支持范围

当前守卫只接受能够证明精确字节与 revision 身份的有限输入，保留受支持的原生 LF／CRLF 路径。显式 `filter`、`ident`、`working-tree-encoding`（包括显式禁用与歧义值）、外部属性来源、自定义属性宏、属性文本中的不受支持编码及 BOM／U+FEFF，以及非规范大小写 `.gitattributes` 等保守拒绝；replacement refs／graft 等会改变对象解释的元数据也拒绝。具体支持边界见 [Architecture](ARCHITECTURE.md) 的 M5 实现说明。

拒绝不是自动转换、清理或修复许可。某些授权后的拒绝会保留未决检查点。不要通过删除属性、改写历史 JSON、手改 SQLite、强制 reset 或清除未知 receipt 来伪造成功恢复。

## 启动、备份、升级与降级

启动方式沿用 [README](../README.md) 和 [M4 Local Usage](M4_LOCAL_USAGE.md)。隔离验收或切换数据环境时，先明确设置绝对数据库路径、Workspace Root、日志目录及必要的 existing Roots，再核对进程工作目录、端口与配置回读。应用有相对数据库路径及用户目录下 Workspace 的默认值；省略配置不代表“独立新环境”。不要在未核对路径时启动旧库。

数据库升级由 Flyway 在启动时执行，当前最新为 V21。V18～V21 添加 Repair、Rollback、Execution Failure、Automatic Rollback 与 Retry 的历史字段；旧行不会因此取得新的授权或变成可恢复状态。损坏或不一致的检查点可能保持不可读并阻塞，不提供自动修补。

真实库升级前先停止写入，保存一致且不覆盖原件的 SQLite 备份并核对 `integrity_check`、哈希、代码版本、实际 Workspace Roots 与副本位置。可以使用 SQLite `Connection.backup`；不得只复制仍在使用 WAL 的主 `.db` 文件来冒充一致备份。详细步骤见 [Local Data Environments](LOCAL_DATA_ENVIRONMENTS.md)。数据库备份不包含 Git 副本，也不能撤销已发生的代码操作。

本项目没有提供 Flyway 向下迁移。旧程序不理解 V16 作用域、V17 删除标记和 M5 新历史；不要将旧程序直接指向已升级库。需要降级时恢复到新的隔离目录中的升级前一致备份，并核对对应代码版本及物理 WorkingCopy；若 Git 已继续变化，旧备份不能成为继续执行的依据。保留升级后原库与所有证据。

## 执行与发布限制

这是受控本机实验能力，不是完整 Recovery 系统，也不是任意不可信代码的 OS 级沙箱。WorkingCopy 与原始资产分离不隔离 Maven 插件、测试或本机程序对宿主的访问；仅执行可信仓库。后端绑定本机使用，不构成具备认证的公网部署方案。

测试或本地 smoke 通过不等于发布许可。付费 Provider、真实用户库、强杀／断电／磁盘损坏和 OS 隔离的证据范围分别记录，不能由确定性测试或正常重启外推。
