# Task D — 规划授权阻塞与资产交互修复

2026-10-11。独立分支 `codex/task-d-asset-authorization-ux`，基线 main
`864361bdd00c8ad90390d938b8d081c07bd31525`。本任务没有调用真实 Provider、重新运行 Smoke、
修改用户业务数据、原始 GS REST 仓库或受管理 WorkingCopy；没有改动 Git、Mutation、
Verification 实现或开始 M5。以下测试使用确定性替身，不能当作新的跨仓库演化 PASS。

## 1. 409 是预期拒绝，缺口在补充流程与提示

实际链路是 `GenerateEvolutionPlanUseCase.generate` →
`EvolutionPlanningService.requirePlanningBasis` → `AssetUsagePolicy.requireEvolutionBaseAllowed`。
§12.6 要求 Git 资产、读取许可、ALLOWED 使用授权和已记录许可证；Analysis 仅要求读取许可。
实际门槛见 [AssetUsagePolicy](../../backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/asset/AssetUsagePolicy.java)。

只读查询 `data/m4-local/delveforge.db` 确认：本机 GS REST 路径有三条资产记录，均为
`read_permission=1 / usage_authorization=ALLOWED / license_info=NULL`。
因此可以完成分析和方向发现，但不得作为 Evolution Base。不是应该删除的 Domain 限制。
此前注册允许未知许可证，却没有原资产授权更新接口；前端缺少演化门槛说明，409 也被翻译成
“版本、授权或状态不允许”，没有告诉用户应该补充什么。

本机观测的当前绑定如下，仅用于恢复这次验收，不是代码或配置常量：

| 对象 | 当前保存的引用 |
| --- | --- |
| 有分析的 GS REST 资产 | `a6c62a1b-a014-43a7-b2d7-c77a8297f681` |
| RepositoryProfile | `58f7c710-76c9-416f-8b45-8e71c35cb2c4` |
| analyzedRevision | `e38e4a1cc1f54c036d40dd111ab50d103b154c06` |
| 已选方向 | `d6dfedfb-3a59-47e7-add5-bbd4de03e688` |

另外两条同路径资产继续保留；不把它们的授权或引用合并到上述记录。

## 2. 最小契约调整

### 显式更新原资产

增加 `PUT /api/software-assets/{id}/authorization`，请求四个字段必须明确提交：

```json
{
  "expectedAuthorizationRevision": 0,
  "readPermissionAllowed": true,
  "licenseInfo": "按实际文件记录许可及适用范围",
  "usageAuthorization": "ALLOWED"
}
```

这里的许可、读取和使用声明必须由用户核对，不能照抄示例制造授权。`licenseInfo: null`
表示仍未知；省略该字段被拒绝，避免无意清空已知记录。允许显式撤销读取许可或改为
DENIED / UNCLEAR，不提供“补许可证就自动允许”的分支。资产没有生命周期 status；
资产存在、字段合法、预期授权版本一致是更新的必要条件。
预期授权版本必须是非负的 64 位 JSON 整数；小数、指数、数字字符串及溢出值返回 400，
不会替用户转换到另一个版本后执行更新。

Domain `SoftwareAsset.updateAuthorization` 返回新的不可变候选，只改变三个授权字段并
推进 `authorizationRevision`。ID、类型、来源和位置保持不变。Application 完成候选校验后
交给 Repository 条件更新；旧版本或持久化失败不修改已加载的原对象。
V14 增加当前授权版本（旧行默认为 0）及登记 guard 表，不删除、合并或升级任何旧授权。
这不是完整授权历史或新状态机。

规划在 AI 之前检查资格，之后重新检查资格与授权版本。SQLite 在最终保存事务中以条件
写锁核对该版本、身份与授权字段，然后保存整个 Plan。即使授权变化后仍然 ALLOWED，
本次旧输入也不能提交；AI 期间不持有数据库写锁。旧的 SELECTED、Profile 归属、
Evidence、revision 和 Plan 插入规则继续执行。

授权拒绝使用稳定原因码：`ASSET_LICENSE_INFORMATION_REQUIRED`、
`ASSET_USAGE_AUTHORIZATION_REQUIRED`、`ASSET_READ_PERMISSION_REQUIRED`、
`ASSET_TYPE_UNSUPPORTED`。旧授权版本用 `ASSET_AUTHORIZATION_CONFLICT`，普通领域
状态 / revision 冲突仍为 `CONFLICT`。错误响应与日志不输出异常 message、许可原文或 Provider 数据。
前端已有执行结果核对流程只补充认识这些新业务码；仍须 GET 证实无 Candidate、干净 READY
才消除等待标记，不自动重试 execute，也不改变任何 M4 生命周期或验证规则。

### 重复注册与复用

浏览器已知列表先作 Windows 词法匹配，显示“已注册”，处理中禁用提交。未知列表通过
`GET /api/software-assets?location=...` 查询真实既有记录；返回多个历史身份时全部展示，
不替用户选择授权。原卡片可跳到“复用已有分析”，补充许可证直接更新原记录。

后端不依赖浏览器判断：新登记事务先取得 SQLite guard 写锁，再比较已有位置、插入新行。
并发重复请求一个成功、另一个 `ASSET_ALREADY_REGISTERED`；不覆盖原记录，即使新请求
提交 ALLOWED 而原记录为 DENIED。低层 `save` 仅保留历史兼容用途，注册和授权更新
Use Case 使用各自的条件写入入口。

`LocalRepositoryIdentity` 只读路径元数据，不扫描 LICENSE、源码或 Git。Windows 大小写、
分隔符、点路径、尾分隔符作保守比较；真实目录链接 / junction 解析到目标。
未创建的叶目录按最近存在的真实祖先比较，悬空链接或不可解析路径失败关闭。
新登记要求绝对本地位置，拒绝相对、drive-relative、UNC 共享和设备路径；旧相对记录
按服务进程目录比较，旧 UNC 只作词法比较。不改写历史 location，不连接网络共享。
新请求的首尾空白在保存前去除，防止去重使用的路径与后续分析收到的位置不一致。

### Evidence 展示

UserProfile、RepositoryProfile 和 Direction 等统一使用 EvidenceList：默认显示
“来自用户描述”“来自仓库文件”，不逐条显示“未确认”。折叠技术详情保留 sourceType、
confirmed 和 origin，并说明单条依据核对不等于整体 Profile 确认或 Direction 选择。
画像审阅页保留依据及核对说明；不把无逐条操作的 confirmed=false 当成完成流程的缺口。
没有改动 Evidence 提取、Domain 值或保存字段。

## 3. 在现有 GS REST 链路上恢复

1. 用本分支构建后的 JAR 重启后端，保留这次验收使用的数据库、Workspace 和浏览器对象 ID。
   原先使用 `m4-local` 就继续使用相同 profile / 环境变量；不要换成空数据库或重新登记。
   正常启动由 Flyway 应用 V14；本任务未替用户启动或修改该业务数据库。
2. 在“仓库理解”找到被当前已选方向引用、已有分析的 GS REST 卡片，展开资产 ID 核对
   上表绑定，选择“完善许可证与授权”。同路径的另外两条记录不用删除。
3. 核对真实文件：本机 `LICENSE.txt` 为 Apache-2.0；`LICENSE.writing.txt` 写明另适用
   `https://creativecommons.org/licenses/by-nd/3.0/`，不能把全部内容笼统记为 Apache-2.0。
   按实际使用范围分别记录代码及教程文字条款；有未解决的限制时保留 UNCLEAR / DENIED，
   不请求演化。系统不作法律兼容性或可商用判断。
4. 用户明确核对读取许可、使用授权和条款后，勾选提交声明，保存当前授权版本。
   也可以先 GET 上表资产，再通过上述 PUT 提交，预期版本始终以 GET 返回为准。
   成功后 ID 不变，授权版本加一；冲突先重新读取并核对，不盲目重发。
5. 回到“演化规划”，保留当前已选方向，选择原 RepositoryProfile，显式生成 Plan。
   无需再次分析仓库、重新确认未变化的画像或重新消费 M2 方向发现。
   这一次 Planning 本身仍是正常的付费 AI 调用，应由用户继续手动验收。

后续 Prepare WorkingCopy、Confirm Step、Execute 的原授权边界保持不变。
本任务只证明前置条件与已有绑定可恢复，不声称新 GS REST 的真实 M3 / M4 已成功。

## 4. 验证

最终 `mvnw.cmd verify` 于 2026-10-11 13:39:11 +08:00 完成，耗时 15 分 38 秒，
BUILD SUCCESS。日志为 `logs/task-d-final-verify-strict-version.log`，并与四个模块根目录
`target/surefire-reports/TEST-*.xml` 的 128 份报告核对一致；不累加私有验证副本的子构建测试。

| 验证 | 实际结果 |
| --- | --- |
| Domain | 216 项通过 |
| Application | 761 项通过 |
| Infrastructure | 261 项通过 |
| App / API | 185 项通过 |
| 完整后端合计 | 1423 项；0 failure / error / skipped |
| `pnpm test` | 5 个文件、44 项通过；只用合成响应 |
| `pnpm typecheck` | 通过 |
| `pnpm build` | 通过，产物位于忽略的 `frontend/dist` |

全量通过后，仅将三个测试类的导入整理为显式导入；对应 18 项再通过，
见 `logs/task-d-final-focused-imports.log`。没有再次修改生产行为。
此前 1422 项的全量通过是增加严格版本输入回归前的结果，不与最终 1423 项相加。
后端构建产物是本分支的新 JAR；未启动它接管用户数据库，也未运行 GS REST 的真实 Planning。
其它聚焦回归与中间失败证据位于主仓库忽略的 `logs/task-d-*`；前端执行记录不作为
真实 Provider 结果。

覆盖许可证未知但可分析、授权完整通过规划、显式补充后沿用原绑定、授权不足不调用 AI、
具体业务码与状态冲突的区分、版本 CAS、AI 期间及最终提交的旧授权拒绝、两线程登记 / 更新、
V13 旧重复记录迁移、真实 junction 别名、Evidence 默认视图 / 技术详情、表单显式授权、
前端重复请求保护、已有确认画像 / 方向 / 执行结果恢复边界。
其中 `RepositoryAnalysisApiIntegrationTest.analyzesRegisteredAssetAndReturnsProfile` 的注册请求
不提供许可证、使用授权为 UNCLEAR，仍获得真实 Use Case 接受并保存的分析快照；
`EvolutionPlanningApiIntegrationTest.explicitLicenseSupplementReusesExistingAssetProfileAndSelectedDirection`
先拒绝未知许可证且不调用 AI，再显式更新原资产并用原请求生成 Plan，断言引用及方向状态不变。

首次受限环境全量验证被 Git for Windows 的 signal pipe error 5 阻断（31 项本地 Git
错误）；这是运行权限问题，不能标为通过。宿主重跑不削弱任何测试断言。
首次宿主全量运行还发现一个旧测试假定相对路径能新登记。它已改为保存历史相对路径夹具，
保留真实 Workspace 分析的 409 / 不调用 AI 断言；新登记拒绝相对路径的 400 另有 API 回归。

提交前的新增 API 回归另确认 Jackson 默认将 `expectedAuthorizationRevision: 0.5` 转成
`0`，原接口返回 200 并推进授权版本。这是新接口的协议问题，不是 GS REST 409 的根因。
只在该字段增加严格整数反序列化，与现有画像版本的做法一致；没有修改全局 Jackson 设置。
回归先失败（预期 400，实际 200），修复后三组 API 共 21 项通过；无效版本不改变资产授权。
对应证据为 `logs/task-d-authorization-version-red.log` 与
`logs/task-d-authorization-final-focused.log`。

## 5. 剩余限制

- 授权元数据是用户声明，系统不自动审查许可合法性、兼容性或实际权属，也没有完整授权历史。
- 旧重复身份保持独立；查找时可能出现多条，需要用户按已有分析 / 方向绑定选择。
- 重复检查扫描当前资产的路径元数据，适合当前小型本地登记规模；不可解析的历史路径可能
  阻止位置查找 / 新登记，按 ID 的授权补充不依赖该扫描。登记后目录替换 / 链接变化未被持续监控。
- Windows 大小写比较保守，可能拒绝 case-sensitive 目录中的不同名字；新登记不支持 UNC。
- 本次没有再次付费验证 GS REST 的 Planning 或演化，生成质量和跨仓库成功率尚需用户验收。
- Java / Maven 有限验证、Windows 宿主无 OS 沙箱、M5 未实现及失败恢复有限的边界继续保留。

契约来源：[Domain §3.2 / §12.6](../DOMAIN_MODEL.md)、
[API 与持久化](../ARCHITECTURE.md)、[本地使用](../M4_LOCAL_USAGE.md)。
