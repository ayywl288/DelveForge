# 验证演化互斥 Tab 与 final-changes 运行版本核对

本轮从 2026-10-18 开始，2026-10-19 完成。审查基线：main `45acc25635ee1b200315b5b5f24dac1dae6f6965`。
承接上一轮未提交的最终差异 / 演化轨迹实现；本轮仍未暂存或提交。未改后端生产源码。

## 本轮修改范围

- `frontend/src/views/EvolutionView.vue`：五个互斥 Tab，ARIA tablist / tab / tabpanel，方向键循环及 Home / End；
  每次只挂载一个主体。标题、状态、操作与失败 / 恢复警告保留在 Tab 外。
  切换 Plan / Step 回到当前状态并重置内容滚动；相同身份的刷新保留当前 Tab。
  桌面主体内部滚动，窄屏使用自然页面滚动；不引入路由或依赖。
- `frontend/src/style.css`：选中、焦点、窄屏换行与内容滚动样式。
- `frontend/src/components/StepFinalChanges.vue`：局部解释 HTTP 404，提示核对后端版本或对象身份，
  不再将旧服务的 INVALID_REQUEST / 404 误述为用户字段错误。保留不可用、匹配版本、迟到响应与卸载代次守卫。
- `frontend/tests/evolution-tabs.test.ts`：新增 5 项，覆盖互斥、只读、键盘、同身份刷新、Step / Plan 切换、
  迟到最终差异及 UNKNOWN / Recovery 提示与 receipt 保留。
- `frontend/tests/step-final-changes.test.ts`：更新 Tab 交互断言，新增旧后端 404 回归。
- `frontend/tests/views.test.ts`、`workflow-ux.test.ts`、`retry.test.ts`：先打开相应 Tab 再检查持久化历史和验证详情，
  保留原有安全、授权、禁止写入和原始内容断言。
- 本文为新增报告；未修改旧验收报告。

## 截图中错误的实际原因

正常开发服务：前端 5173，经 Vite 代理访问后端 8080。只读读取当前项目与已成功 Step，
请求 URL 为 `/api/evolution-plans/{planId}/steps/{stepId}/final-changes`，无查询参数、无请求体。
实际响应为 **HTTP 404**，响应体 code 却是旧服务的 `INVALID_REQUEST`，因此旧通用错误映射显示“请求字段无效”。
这不是已证实的 HTTP 400 或 Git Tree 拒绝。

8080 的 Java PID 34632 使用 `backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar`，
文件时间 2026-10-18 14:41:36。ZIP 清单确认不含 `StepFinalChangesController.class`。
上一轮隔离 JAR 与本轮新构建均包含该控制器。本轮没有改查询参数或放宽 Git 约束，
也没有替换 / 重启真实用户服务。
证据：`live-final-changes-before.json`、`runtime-jar-comparison.json`（完整请求身份仅保存在本地证据中）。

诊断时，正常服务需要在确认空闲并正常停止后，通过既有 `setup-delveforge.ps1` 重新构建并启动；
只刷新浏览器不能给旧 JAR 增加接口。不得在有未决写入时直接重启服务。

## 本轮自动验证

证据目录：`E:/develop/DelveForge/logs/evolution-tabs-20261018`。原始日志和退出码均保留。

| 验证 | 实际结果 | 退出码 |
| --- | --- | --- |
| `pnpm --dir frontend test` | 15 文件，218 tests，0 failed，0 skipped | 0 |
| `pnpm --dir frontend typecheck` | 通过 | 0 |
| `pnpm --dir frontend build` | 通过 | 0 |
| Maven Wrapper 离线 reactor 定向 `verify` | 17 tests，0 failures / errors / skipped，4 模块构建和 Boot repackage 成功 | 0 |
| `git diff --check` | 通过 | 0 |

后端选择器：`GetStepFinalChangesUseCaseTest` 7、`GitWorkingCopyTreeDiffIntegrationTest` 8、
`StepFinalChangesControllerTest` 2。完整命令和 Java / TEMP 隔离设置见 `run-backend.ps1`、`backend-command.txt`。
源码快照与新 JAR 位于 `target/et18`，不会覆盖正在运行的旧 JAR或上一轮 `target/eui18`。
本轮没有重新运行 1,936 项完整后端回归，亦不将上一轮 99 项或 212 项作为本轮结果。

首次前端运行保留为 `frontend-initial.log`：213 项中 204 通过、9 失败，
失败原因是旧测试直接检查当前已移到其他 Tab 的内容；更新为真实切换 Tab 后保留全部原断言。
最终运行见 `frontend-tabs.log`（218 项）。

## 真实 Edge 与真实只读接口

真实 Edge 独立 origin `http://127.0.0.1:15377/`，服务 `frontend/dist` 实际构建。
预览入口和轨迹使用明确标注的合成 GET 夹具；**成功 Step 的 final-changes 由 18087 新 JAR 实际响应**，
经预览服务只读转发，不用路径合集或硬编码列表冒充后端结果。所有非 GET 请求被拒绝。

18087 显式使用 `target/et18/browser.db` 与 `target/et18/managed`，不连接真实用户库或副本。
测试库来自上一轮已停止实例的空合成 V21 库，通过 SQLite backup API 复制，随后仅写入本轮合成事实。
未复制真实用户数据库。Provider 地址限制为本机不可用端口，未调用 AI、Execute、Retry 或 Repair。

合成 Git 历史：baseline → 中间 Candidate → 已验证 Candidate → 后续提交；另留未跟踪文件。
固定 Step 端点实际比较返回 HTTP 200 / AVAILABLE，新增 10、修改 9、删除 9。
已还原的文件、后续提交文件及未跟踪文件均未混入结果；历史 Verified Revision 不等于当前 HEAD。
查询前后 Git 全部文件 hash 与所涉及的领域表记录相同。见 `read-only-verification.json`、
`synthetic-fixture.json`、`isolated-final-changes-correct-fixture.json`、`real-backend-responses.jsonl`。

第一次隔离夹具查询因技术标准 description 未使用 Domain 固定文案而返回 400，
失败 JSON / 日志和初始脚本完整保留。仅修正本轮合成夹具的文案后返回 200，
没有改 Domain / 持久化生产代码或真实数据库。修正脚本为 `correct-synthetic-criterion.py`。
该夹具错误与真实服务缺失接口的 404 分开记录。

实际 Edge 检查：

- 五 Tab 互斥，选中状态、键盘 Right / End 与焦点正确；切换不改变 URL。
- 完整历史展开后“刷新已知对象”，仍保留历史 Tab 和展开内容。
- 切换 Step 回到当前状态；窄屏焦点进入新 Step 面板。
- 最终变更展开 28 个真实 Git 文件；Verification 详情、轨迹、历史均可独立查看。
- 390×844 视口下五 Tab 换行、长路径换行；内容宽与滚动宽均为 375 CSS px，无整页横向溢出。
- RECOVERY_REQUIRED 的标题、警告与恢复未确认提示，在完整历史 Tab 下仍可见。
- 已恢复浏览器临时视口设置。未操作用户 5173 页面中的执行 / 授权按钮或清理旧存储。

截图：`edge-real-final-changes.png`、`edge-trace.png`、`edge-history-refresh.png`、
`edge-narrow-final-changes.png`、`edge-blocked-history.png`；另有 Step 重置与阻塞页 AX 文本。
真实浏览器验收与 Vitest / jsdom 分开；轨迹是合成历史展示，不声称真实 Provider E2E。

## 运行与边界

已保留 15377 的只读预览供查看，其 final-changes 依赖专用 18087 合成实例。
预览脚本为证据目录下 `preview-server.mjs`；18087 启动命令及 PID 见 `isolated-backend-command.json` 和 PID 文件。
这不是正常开发数据环境。正常用户服务现已由用户自行重新构建并重启，补核结果见下。

六张未跟踪设计图 SHA-256 与开始时相同。原草稿、日志、数据库、旧 JAR 与历史证据均保留。
未改 Domain / Migration / Recovery / Git mutation / M5 状态机 / 安全授权及其它页面。
M5 PARTIAL 与 Recovery 延期不变。未提交、Push、发布或创建 Tag；完成后等待用户查看。


## 用户自行重启后的补核（2026-10-19）

用户确认已将后端重新打包并正常重启，并已在页面看见最终变更文件。
随后仅重复同一个真实 Step 的 final-changes GET：HTTP 200 / AVAILABLE，**12 个文件，新增 8、修改 4、删除 0**。
没有执行 / Retry / Repair / Provider 请求，也没有直接修改真实数据库或 Git 文件。
最新正常 JAR 文件时间为 2026-10-19 11:10:04，包含 `StepFinalChangesController.class`。
这关闭了此前已确认的前后端版本不一致问题；不需要继续调整 API 或 Git 安全限制。
完整响应与产物检查另存 `live-final-changes-after-user-restart.json`、`runtime-jar-after-user-restart.json`，
原 404 证据仍保留。

本次补核尝试读取用户已有 Edge 标签页时浏览器连接未附加，未取得重启后真实业务页面的新截图。
页面可见性来自用户确认；HTTP 200 / 文件数量来自本轮直接 GET，二者分开记录。
前述真实 Edge 的互斥 Tab、窄屏与隔离真实 Git 后端检查及截图仍有效，不冒充重启后用户页面截图。
