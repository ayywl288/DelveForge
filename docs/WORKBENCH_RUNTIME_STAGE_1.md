# 项目级运行环境验收

基线为 `0ef702e52b3ddf28a27e27560d58dbcba9ec6aad`。本阶段的源提交可用 `git log -1 --format=%H -- docs/WORKBENCH_RUNTIME_STAGE_1.md` 定位。本机复验日志和资源身份在 `target/project-isolation-20261115`。

## 运行方法

为 Docker 测试创建单独的数据目录，先用 `runtime/workbench/Initialize-Environment.ps1 -DataDirectory <测试目录>` 初始化，再将 `DF_WORKBENCH_TEST_FIXTURE` 指向其绝对路径。在仓库根运行：

```powershell
.\mvnw.cmd '-Dtest=LocalWorkbenchRequestsTest,Workbench*Test,ProjectSchedulingTest,ParallelProjectDockerTest,ConversationCompactionTest,ProjectWorkcopiesTest,SourceFolderSelectionTest,DeepSeekTokenCounterAdapterTest,SpringAiAgentModelTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
```

前端在 `frontend` 运行 `pnpm test`、`pnpm typecheck`、`pnpm build`。模型使用确定性替身，工具使用真实 Docker Desktop Linux 引擎。

## 观测

- 后端 88 项通过，包含 6 项真实 Docker 用例，跳过 0 项。前端 407 项通过，类型检查与生产构建通过。
- A、B 同时保持已准入的 Bash，各自进行 Maven 安装与消费。A 的原容器为 `21f2a8f1b294`，B 为 `2d9cdcd8862b`。同坐标产生不同 SHA-256，两个消费程序分别输出 Alpha 与 Bravo。
- 两个独立服务使用 8086，各自取得 HTTP 和 WebSocket 响应。对方工作副本、服务、输出回执不能从本项目读取。A 的第二段对话及第三个项目在容量释放后自动执行；A 重启、重建期间 B 的服务持续可用。A 的替换容器为 `9abe83a57d50`。
- 杀掉同时持有两个真实命令的 Java 控制进程，恢复后两份工作副本各只有一条副作用。两条回执均保留已捕获的 stdout、stderr 及观测缺口，等待任务自动运行。SQLite 的竞争测试使用两个连接、12 个同时到达的请求。
- 使用另一组旧卷夹具迁移项目 HOME。属于其他项目的文件和旧混合 Maven 缓存均未复制。日常环境原有 4 个已停止容器及数据卷均保留。旧开发容器与基线镜像的 171 项已安装 APT 包版本一致，非 APT 文件的归属未知。

- Edge 和 Windows 原生目录选择器实际完成取消、选择、提交与编辑独立副本。回归核对源目录内容未变，浏览器异常 0 条。更新后的网关和故障恢复另复验 2 项，均通过。
