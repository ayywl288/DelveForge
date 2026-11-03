# 初次前端检查实现验证（历史）

从原 AGENT_FRONTEND_CHECKS 文档迁移；以下版本、镜像及数量仅表示当时执行，未重新运行。
当前操作说明见 [本地运行](../LOCAL_SETUP.md#agent-build-preflight)。

## 2026-11-02 初次 v4 实现验证记录（历史）

实现分支为 `codex/full-stack-evolution-checks`。以下为初次实现时的本地运行，不是后续 v5 的重新执行结果。
该批验证未调用付费模型或操作真实业务数据库、
WorkingCopy；Git/SQLite 交付使用测试创建的隔离 fixture。

| 检查 | 实际结果 |
| --- | --- |
| Maven Wrapper 完整 `verify` | 1818 项，0 failure、0 error、40 conditional skip；domain 209、application 857、infrastructure 530、app 222 |
| 启用 Docker 的定向 Java 回归 | 97 项，0 failure、0 error、3 conditional skip；包含下方实际 smoke 两项 |
| npm/pnpm 完整全栈 smoke | 两项均执行并通过，0 skip；真实安装、Maven/Vue 检查、Candidate 冻结及 Delivery |
| 最终镜像 Python 策略与代理回归 | 48 项通过，包含锁文件、缺依赖、失败结果、源码修改、超时及代理限制 |
| 最终镜像协议回归 | 7 项通过 |
| DelveForge 前端 | 22 文件、303 项测试通过，typecheck 与 build 通过 |
| 最终本地打包 | `mvnw.cmd -q -DskipTests package` 通过；打包前端检查资源与源文件 SHA-256 一致 |

完整 `verify` 的条件跳过不代表 Docker 集成已执行；Docker 回归在单独启用环境变量的运行中完成。
每种包管理器的 smoke 都真实经历锁文件不一致、Vitest 失败、类型错误及构建失败，再由确定性模型动作修改源码，
取得当前源码的 Maven test、Vue typecheck/build/test 结果。最终两份交付均为 `COMPLETED`，
原始资产与正式交付仓库均无未提交变更，正式仓库没有 node_modules。

真实 smoke 使用当次 v4 镜像 `sha256:c7fc26c21b235bf1e85c36deb64da045b460f5ee309dbc0d3cc26dee067d8166`。
此后仅增加 pnpm 锁中非常规包名/本地来源 key 的拒绝规则；两份真实 fixture 锁通过最终规则验证，
最终镜像另行通过上述 48 项策略/代理与 7 项协议测试，未把这次负向校验补充描述为重新执行完整 smoke。
正式镜像初始化流程已构建并核对当前 17/21 镜像脚本及固定工具链版本：

- JDK 17：`sha256:d35b880463265ffc100a4054b5bdb28e5f1402c37cf70beaa8fc719b45471927`。
- JDK 21：`sha256:4aed4db85923dfbf66173c3a78f9f63f07e8bc51826526a5b4426312ce4d361b`。

本地可再生成的记录保存在忽略的 `target/backend-final-verify-summary.json`、`target/fullstack-focused-summary.json`、
`target/fullstack-smoke-junit.xml`，以及 infrastructure 的 `target/fullstack-smoke-{npm,pnpm}.json`。
默认后端测试再次运行会覆盖其 Surefire 条件跳过记录，因此真实 smoke JUnit 已单独保留。
以上证明受控工具及交付链路；没有执行浏览器交互、应用启动或最终产品业务验收。
