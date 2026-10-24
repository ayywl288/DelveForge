# 补丁冲突、执行预算与停止交互

基线 main@b67a465e510481e64980755c7508b0b5103d70d1，隔离分支 codex/patch-conflict-limits。

## 真实记录与不能证明的部分

默认 SQLite 仅以 mode=ro / query_only=ON 查询有限元数据。Run b38427cf-a973-4723-bbab-cbc8cc2d3d54
于北京时间 2026-10-24 10:21:48 至 10:31:44，FAILED / MODEL_CALL_LIMIT，60 model、97 tool、3286657 tokens，
Candidate 为空。记录有 27 次 PATCH_CONFLICT、11 次 PATCH_APPLIED；冲突事件没有路径、Git 失败原因或补丁正文。
不能证明具体冲突是旧上下文、行尾、重复补丁、格式或环境造成，也不能判断是否重复了相同正文。
没有为调查重跑真实任务、读取并输出原始补丁或修改真实 WorkingCopy。

## 隔离复现与修正

真实 Git 在临时 Java/Maven 夹具中验证：多个 hunk 以及匹配的 LF/CRLF 能成功应用；先成功修改后再使用旧
上下文会冲突；重复已存在的变更也会冲突；LF 补丁不能直接匹配 CRLF 源码。原有解析器没有在这些合法场景
误判 hunk 行数，本次不修改它，也不使用忽略空白/模糊应用来掩盖冲突。

已证明的缺口是反馈和结果分类：旧工具把 Git 检查失败与检查成功后的应用失败都返回 PATCH_CONFLICT，
外层还 ok=true；没有明确的无修改证据和修正提示。新反馈区分检查失败与应用后未知，固定原因允许名单
包括 CONTEXT_MISMATCH、CHANGE_ALREADY_PRESENT、INVALID_DIFF、PATH_STATE_MISMATCH、CHECK_FAILED。
Git 自由文本不再进入补丁反馈；原始行尾保留，不自动重写文件。反向检查只读，不用于反向应用。
新的永久回归先在旧实现运行失败，再验证新结果；已成功修改在后续冲突后保持。

三次尚未解决的冲突会停止；重复的同一解码补丁在进入沙箱前停止，已执行结果按调用身份缓存不重执行。
CRLF/LF 不能合并为一个比较键，因为改变行尾本身可能是有效修正。仅在应用成功后清除修正历史。
压缩历史保留固定冲突信息、安全目标与重新读取提示，保留已有 Maven 检查范围和未解决失败语义。

## 默认预算与停止生命周期

次数默认 60/160 改为 0=只统计；保留正数显式预算。表示溢出以 USAGE_COUNTER_OVERFLOW 停止，不回绕。
保留 1800 秒墙钟、180000 字符上下文、24000 字节输出、每响应至多 16 个工具、补丁/文件/快照/资源
及 Maven 时间、依赖修正预算。没有自动模型重试；外部 API 计费且不能恢复中断对话。

唯一粘性停止控件指向当前步骤活跃 Run，包括查看历史时；请求中防重复，接受后“正在停止”，
直到真实终态。迟到快照不撤销停止事实；旧 Run 响应不覆盖当前选择。后端回归模拟不能立即中断的模型，
先确认取消请求、后实际取消及清理，不冻结 Candidate。资源协调和 Delivery 逻辑未改。

## 展示与证据范围

补丁尝试只进入一个稳定汇总，分类计数并仅预览已成功应用的安全路径；默认折叠、每次增量 40 行。
拒绝/结果不明警告在主监视器可见，重要终态仍保留。无百分比的指示条仅用于真实活跃操作，
终态与历史静止；减少动态效果覆盖指示条、计数强调及原位转换。

实际命令、最终数量和浏览器夹具结果在交付报告中列出。合成复现不能证明原 Step 现已完成；
没有真实 Provider / Docker Agent 验证，没有迁移真实数据库或合并/推送。

## 2026-10-24 实际验证

```powershell
.\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=AgentPatchCorrectionsTest,AgentToolActivityTest,CodingAgentServiceTest,DockerPatchPolicyTest,SqliteAgentStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# PASS：96 tests，0 failure/error；最终新增两项 Java 安全回归前执行。

$env:DELVEFORGE_AGENT_LIVE='false'
$env:DELVEFORGE_AGENT_E2E='false'
$env:DELVEFORGE_AGENT_DOCKER='false'
$env:DELVEFORGE_AGENT_COMPATIBILITY='false'
$env:DELVEFORGE_TASK2_E2E='false'
.\mvnw.cmd verify '-Dmaven.jar.forceCreation=true'
# PASS / BUILD SUCCESS：1549 total，1527 executed passed，22 skipped，0 failure/error。

python -B -m unittest discover -s backend/delveforge-infrastructure/src/test/python -p test_patch_feedback.py -v
# 修复前 RED：5 methods 中 4 ERROR（缺少安全反馈及应用后未知类型）；多 hunk / 匹配 CRLF 已通过。
python -B -m unittest discover -s backend/delveforge-infrastructure/src/test/python -v
# 最终 PASS：21 methods（含多个 subcases），额外执行，不计入 Maven 总数。

pnpm --dir frontend test
# 最终 PASS：16 files / 239 tests。
pnpm --dir frontend typecheck
# PASS。
pnpm --dir frontend build
# PASS。
git diff --check
# PASS。
```

开发中前端旧选择器/文案与新汇总不匹配的断言曾失败，迁移后保留分类、顺序、时长与安全断言。
停止终态测试最初没有推进既有 200ms 轮询计时，改成虚拟计时后验证实际回读；另新增迟到快照不回退终态的回归。
默认预算夹具达到 62 model / 183 tool 并保持准确计数；显式正数预算与墙钟测试仍通过。

浏览器：完整 EvolutionView 与真实 CSS、合成 API/EventSource，禁用真实后端连接；桌面滚动至补丁记录后，
停止控件 position=sticky、top=12px；100 条补丁仅初始渲染 40 条，随后 80→100，主时间线补丁行=0。
查看历史时停止按钮仍针对活跃 Run，接受后“正在停止”，历史选择不变。390px 窄视口已视觉核对。
临时页面、服务和标签已清理。该大历史夹具仅用于展示，不是新纠错预算下某个真实执行轨迹。

限制：未执行付费 Provider、真实 Docker Agent 或用户 Step；不能声称原任务现可完成。
减少动态效果有 CSS 回归，未切换系统偏好实测。重复检测只比较解码后的相同补丁文本，不做任意语义等价分析。
Python 输出预算夹具沿用 Windows 对 Linux 进程组 API 的替身，不证明实际 Linux 清理。
持续到 Run 结束的身份/结果缓存仍消耗内存；外部调用的中断响应可能延迟，停止不是暂停或续跑。
