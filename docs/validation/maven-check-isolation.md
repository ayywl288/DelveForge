# 正式 Maven 检查隔离验证

日期：2026-10-27（Asia/Shanghai）。起点 `2c291e5`，分支 `codex/maven-check-isolation-resume`。
完整本机证据：`E:/develop/DelveForge/data/maven-check-resumed-20261027/`。

## 既有修复复核

`2c291e5` 确实将在线 Maven 准备放到 `prepare-source` 副本，权威 Agent 源卷只读，原 goal 与缓存复用保留。
前端确实复用 `isPatchTool`，同时移除重要时间线中的 `apply_patch` 和 `edit_file` 普通明细，汇总仍保留。
这解决了准备和重复展示；正式 `run_checks` 原先仍直接在可写 Agent 源卷执行，尚未解决版本化运行期数据。
前一轮报告、失败 Run 和计划未改写；前一轮前端 256 项结果属于保留证据，本轮没有前端源码变化。

## 无模型复现

固定 Taskarium 原始提交 `91f69ab1d7ab3a654814a1971cd3871ab0dffb1d`，使用真实 JDK 17 可信镜像、
原测试、离线缓存和正式 `sandbox.checks(test)`。一项测试实际通过，随后正式检查以 `CHECK_MUTATED_SOURCE` 拒绝。
唯一变化是 `database/taskarium_db.mv.db` 的内容，mode 保持 `100644`；没有其他源码或配置变化。

[修复前路径/哈希与报告证明](evidence/maven-check-isolation-before.json)。它来自本轮确定性副本，
不是已被清理的历史 Run 字节恢复。H2 写入含时间相关数据，所以重复运行的后像哈希可以不同。

## 局部修复与证据含义

- 每次正式检查使用无网络 `check` 容器和一次性 `check-source` 卷；原 Agent 源卷只读挂载到 `/source`。
- 复制前完整 snapshot 包含数据库初始字节。副本返回的 snapshot 必须一致；宿主还核对权威源前后身份。
- 只有已有 `.mv.db` 文件且具有 H2 MVStore 头部标识的内容变化可在副本中接受；逐项记录
  `H2_MVSTORE_CONTENT`、相对路径及前后 mode/hash。没有对路径、任意后缀或任意二进制增加忽略规则。
- 其他源码、配置、二进制、新增/删除材料和模式变化继续拒绝；全部检查副本写入均不进入 Candidate。
- 生成测试报告先清理后读取，并与执行前完整 snapshot 关联；冻结时仍按最终源码判断 stale。
  `runtimeChanges` 也进入 Candidate 测试证据，不冒充最终数据库或被交付源码。
- 完成、取消、启动恢复清理均覆盖新容器和卷；保留非 root、只读根、资源限制和离线依赖执行。

[修复后测试与临时变化证明](evidence/maven-check-isolation-after.json)记录一项真实测试通过，
返回 snapshot 与原始输入相同。该直接工具复现的后像是待丢弃的检查副本；真实 Docker adapter 回归
另行断言权威 Agent 全量 snapshot、原库字节与 Git 状态保持不变，并验证真实源码写入仍拒绝。
本次只扩大已证实的 H2 运行兼容；其他数据库格式保持保守拒绝。每次复制与重新构建有额外开销。
运行策略与代价已追加到 ADR-0012，没有改变 Domain、Application Port、数据库结构或 Candidate/Delivery 合同。

## 验证结果

| 验证 | 新执行结果 |
|---|---|
| 修复前真实 Docker adapter 回归 | 1 项以 CHECK_MUTATED_SOURCE 错误失败，保留 docker-red.xml |
| 修复后真实 Docker adapter 回归 | 3 项，零失败/错误/跳过；真实 H2 测试成功、权威源保持、违规源码仍拒绝 |
| Python 工具全量 | 42 项，7 项镜像门控跳过，实际 35 项，零失败/错误 |
| 后端全量与打包 | 166 份 XML、1702 项，36 项门控跳过，实际 1666 项，零失败/错误；mvnw.cmd -o verify exit=0 |

[机器可读验证汇总](evidence/maven-check-isolation-verification.json)包含实际镜像、源码及 JAR 哈希。
本机原始日志、修复前后 XML 和全量 XML 压缩包在文首目录保留。

没有修改 Taskarium 业务代码、配置或原仓库，没有消耗 DeepSeek tokens。
通过本阶段后才允许沿原四步 Plan 以新的合法 Run 续跑；续跑结果另见后续验收报告，不能从本阶段推断业务成功。
