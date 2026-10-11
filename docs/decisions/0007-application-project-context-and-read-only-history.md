# ADR-0007：Application 项目上下文与只读历史恢复

Status: Accepted

Date: 2026-10-11

## 问题与既有边界

用户需要保存尚未生成 Direction / Plan 的流程，并从历史 Plan 恢复业务上下文。
UserProfile 可复用，Asset 可多次分析、规划；路径、名称和时间都不能证明对象属于同一个项目。
既有 Domain 没有覆盖探索阶段的流程身份，但已经保存 Plan → Direction / Base Profile / Asset、
Plan → WorkingCopy / Steps 及结果的明确引用。

Task F 只解决导航和恢复。`INV-D09` 的全局唯一 SELECTED Direction、Step 授权、顺序、
revision 和 Verification Before Trust 保持原样；项目切换不是新的并行演化授权。

## 当前决策

- 在 Application 定义 `ProjectFlowContext`：身份、名称、明确关联的对象 ID、时间和并发版本。
  SQLite V15 新增一张表；不回填、改写或删除既有业务记录。
- 一个上下文一旦关联 Profile 或 Plan，不得重新绑定另一身份；资源 ID 集合只可追加。
  另一个 Plan 使用另一个上下文。`expectedVersion` 的数据库 CAS 防止旧页面覆盖新记录；
  Plan 的唯一索引及原子认领防止并发恢复产生两个项目身份。
- 复用 Profile 时只允许当前 CONFIRMED，并在插入语句核对同版状态；不复制或修改画像。
- Application 历史服务只通过 Persistence 端口读取聚合，通过只读 Workspace 端口预检目录。
  不获得 AI、Git mutation、构建、验证或生命周期提交能力。
- 旧 Plan 沿保存的引用形成只读投影。用户点击继续后才认领导航上下文；普通 GET 不写入。
  缺失或矛盾引用显示明确问题，不按相同路径或“最新记录”补齐。
- 返回真实 Step 状态、ExecutionResult、VerificationResult、当前画像以及 Direction 绑定版本。
  当前画像不能冒充历史版本内容；未持久化聊天、问题、CodingProposal 不可恢复。
- Vue 保存活动项目 ID；恢复 GET、业务命令、上下文保存是不同操作。业务命令成功后，
  再单独 CAS 保存关联。关联失败须说明业务可能已完成、禁止自动重放并要求重新查询。

## 替代方案与取舍

仅保存浏览器 UUID 无法提供跨刷新、重启的项目历史，也无法可靠保存早期流程。
按仓库路径拼装会混合不同 Plan / 授权记录；引入 Domain EvolutionFlow 与第二套状态机
会扩大 Task F，并改变当前活动流程约束。因此采用最小 Application 导航记录。

上下文与原有业务命令不共享事务，异常时可能存在尚未关联的业务对象。前端明确呈现这种
区别；资源目录可选择已有 Profile / Asset / RepositoryProfile，已保存 Plan 始终可沿其引用
查询。没有 Plan 的旧 Direction 不会被猜测性地拼成历史项目，仍可通过高级 ID 入口读取。

目录预检只检查配置允许根目录、规范化位置、存在性、链接重定向和可读性，不检查 Git HEAD
或代替执行守卫。跨聚合 GET 不是整个业务流程的原子快照；执行前仍由原 Use Case 检查实时
状态、授权与 revision。分页采用 offset，活动记录变化时用户可以刷新列表重新核对。

## 影响范围

Application 项目服务与端口、Infrastructure SQLite 与只读目录预检、App REST 投影、Vue
工作台与上下文隔离。无新依赖、无 Maven 模块变化、无 Domain 状态转换或 M5 实现。

接口与边界见 [ARCHITECTURE](../ARCHITECTURE.md)。
