# 当前活动面板与 SOURCE_CREDENTIALS 调查

## 实际证据

基线为 main@54322648f7a76e3fa36ce29791c86a0669ff22c4。
真实 SQLite 仅以 mode=ro / query_only=ON 查询有限元数据，没有写入、迁移、恢复或重跑。
Run 86620f00-6406-4a8e-a882-b093acfa37d1：北京时间 2026-10-24 00:25:19 至 00:28:15，
FAILED / POLICY_REJECTED，11 model calls、46 tool calls、191476 tokens，Candidate 为空，retained=false。
事件 426 TOOL_STARTED apply_patch 与 427 TOOL_FINISHED 使用同一工具活动身份；后者为
SOURCE_CREDENTIALS / PATCH_SOURCE、ok=false、resultRecorded=false，调用耗时 211131400 ns；428 FAILED。

拒绝事件没有目标字段或补丁正文；retained=false，默认 Agent 根目录没有该 Run 的保留目录。此前 read_file 的目标只证明读取过哪些文件，
不能证明最终补丁目标，因而未用它推断命中文件。已检查默认运行日志也没有补足相关诊断。
**无法确认实际目标、安全文件类别、具体 sanitizer 子规则、凭据真假或补丁是否仅修改无关行。**
不把此前两个通用 POLICY_REJECTED 事故推定为同一规则。

## 已确认执行路径

DockerAgentSandbox.tool → invoke(inspect_patch) → requirePatchContent。
inspect_patch 在 sandbox.py 中只验证 patch_paths 并读取现存目标文本，不执行 Git/源码写入。
requirePatchContent 对每个目标再次 requirePath，然后比较完整原文件与 sanitize(original)。
不同即抛出 SOURCE_CREDENTIALS / PATCH_SOURCE，在 invoke(apply_patch) 之前退出。
模型读取材料仍通过同一个 RepositorySecretPolicy，日志和事件不保存这些原始文本。

DeterministicRepositorySecretPolicy 依次处理私钥块、已知令牌形态、Authorization 值、URI userinfo、
凭据字段赋值。最后一类也覆盖普通代码中的 `int token = 0`，不区分配置占位、样例和真实秘密。
这是 ADR-0006 的确定性政策及其代价，不能据此断定实际文件存在真实有效的凭据。
新回归分别用合成凭据字面量和普通代码触发同一整文件拒绝，实际修改保护值、无关行、脱敏写回均拒绝；
没有秘密的其他文件仍通过原检查。原有禁止路径、symlink、补丁凭据和捕获限制均保留。

## 当前策略与取舍

模型可见内容仍由既有形态规则净化，不承诺识别未知格式。整文件拒绝阻止对已识别凭据所在原文件的改写，
避免脱敏占位符覆盖，也避免把针对净化文本生成的差异直接应用到原文。
它是保守策略，不代表精确定位补丁是否触及秘密；代价是普通代码变量、示例配置也可能不可编辑。

本次不实现秘密所在文件的局部编辑，保留原有安全断言。当前协议只有统一 diff 与净化文本，
没有原始秘密跨度与模型可见跨度的对应关系，也没有支持此类部分改写的原子提交能力。
“补丁没出现秘密”或“秘密字符串仍存在”都不足以证明原始值、所属位置和所有其他保护内容未改变。

后续待审设计需要：受控适配器持有原始快照和净化映射，在隔离候选中预演补丁；路径/归属/链接重验；
证明全部原始保护跨度逐字节且在原位置保留，拒绝占位符写回和新增秘密；最终全量净化与校验；
用明确的提交边界保证多文件失败原子性，未能证明无写入时不恢复。模型只接收净化文本和固定失败分类。
不采用单纯放宽正则、凭据字段白名单或直接删除 SOURCE_CREDENTIALS 保护。

本次实现更窄的反馈：Docker 在上述修改前检查点通过专用工厂标记未修改；Application 每 Run 最多一次
返回 ok=false 的固定拒绝信息，让模型保留该文件并另选路径。只缓存原调用结果，不自动重打补丁。
第二个不同调用再次命中终止；相同 identity 不重新执行，改参数复用 identity 仍冲突。
未知或应用后失败不能使用此标记，也不从通用规则/阶段推断。记录反馈失败保留原 POLICY_REJECTED。
拒绝不计为补丁成功，不会直接产生 Candidate 或 Delivery；最后仍须实际变更与既有验证。

**已解决的是已证明修改前拒绝的一次有界换方案机会；没有证明真实任务可以避开受保护文件而完成。**
如果实现必须修改该文件，仍将受保护地阻止，需单独审阅局部编辑设计。

## 展示与回归范围

执行监视器分为：固定当前活动、重要操作、单一 Run 文件探索汇总。普通模型请求不再重复占行；
模型统计取实际 Run.modelCalls，当前请求序号只取持久化 request=N。只有真实未决请求有等待点；
终态、取消和历史活动静止。重要操作保持真实顺序和 220ms 进入效果，减少动态效果关闭动画。
探索汇总继续最多 4 个真实安全预览、分类计数及每次 40 行增量展开；文件操作不重复出现在主时间线。
原始事件不删除，SSE/回放/去重/重连/Run 选择逻辑未改。

Java 回归覆盖保守凭据拒绝、原始失败保留、有界反馈、重放缓存、身份冲突、第二次拒绝终止、
无修改时不能冻结、应用后不恢复。Python 临时夹具验证 inspect_patch 不执行命令或改变文件。
Vue 回归覆盖当前面板原位更新、模型统计、重要操作、探索分页、静态历史、滚动、连接与安全诊断。
不以此记录代替真实 Docker 或 Provider 验证。

## 2026-10-24 实际验证

在隔离工作树执行以下命令，未连接用户数据库或真实 Provider：

```powershell
.\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=AgentToolActivityTest,CodingAgentServiceTest,DockerPatchPolicyTest,DeterministicRepositorySecretPolicyTest,SqliteAgentStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# PASS：110 tests，0 failure / error；执行于最终新增 3 个 Java 测试之前。

$env:DELVEFORGE_AGENT_LIVE='false'
$env:DELVEFORGE_AGENT_E2E='false'
$env:DELVEFORGE_AGENT_DOCKER='false'
$env:DELVEFORGE_AGENT_COMPATIBILITY='false'
$env:DELVEFORGE_TASK2_E2E='false'
.\mvnw.cmd verify '-Dmaven.jar.forceCreation=true'
# PASS：1537 total，1515 executed passed，22 skipped，0 failure / error；包含最终 Java 测试。

python -B -m unittest discover -s backend/delveforge-infrastructure/src/test/python -v
# PASS：15 methods（含多个 subcases），不计入 Maven 总数。

pnpm --dir frontend test
# PASS：16 files / 232 tests。
pnpm --dir frontend typecheck
# PASS。
pnpm --dir frontend build
# PASS。
git diff --check
# PASS。
```

开发中一次 Maven 测试编译因缺失 assertFalse 静态导入失败，补齐后通过；前端两处旧断言依赖旧布局/
CSS 首个块，已改成分别验证主时间线与探索顺序，以及明确验证减少动态效果规则。没有删除安全断言。
浏览器合成夹具初次使用运行时 template 无法渲染，改用 Vue render 函数后验证；生产组件无此问题。

浏览器使用真实组件/CSS、合成事件和禁止后端访问的页面：桌面与 390px 窄屏可读，当前活动原位从模型请求
切换到读取/拒绝/终态；无重复模型行；汇总保持单一并按 40→80→97 展开；切换 Run 后折叠重置。
临时服务、页面及标签已清理。未执行真实 Docker/模型/用户 Run；减少动态效果由永久 CSS 回归覆盖，
未切换操作系统偏好实测。Python 输出预算夹具沿用 Windows 对 Linux 进程组 API 的替身，不证明实际 Linux 清理。
