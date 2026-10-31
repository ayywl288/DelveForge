# V2 本地验证与交接

2026-10-31 · Precision Room · 待产品负责人独立视觉验收

起点已实际核对：`E:\develop\DelveForge`，`codex/frontend-visual-identity`，
`165b3c063cc2d6ca49e80e55f242d8ca7992a558`；tracked clean，只有受保护的
`frontend/design/` 与 `grilling/` 未跟踪。按要求从该 HEAD 创建 `codex/frontend-experience-v2`。
V1 两个提交与原审阅目录保留。没有 push、merge、PR、部署或发布。
V2 实现本地提交：`c6f13cc`；本目录的画廊与报告在随后独立的文档提交保存。

## 范围与来源

实现只涉及 Vue 展示、页面组成、CSS、SVG 品牌配色、响应式焦点、对应测试和本地审阅材料。
没有修改 Java、迁移、Provider prompt、API client、session 业务状态机、后端合同或依赖/lockfile。
方向选择/拒绝、画像 revision 确认、资产授权、准备副本、Step 授权、费用确认、停止与重交付仍调用原行为。
AgentResultSurface 只承接原展示模板与 deliver 事件；交付接受条件仍在原执行页和后端。
Run 成功只表示冻结编码结果；Candidate、正式交付与接受文件分别显示，NOT_TESTED 不冒充通过。

所有截图是隔离合成数据下的真实 Vue 界面。V2 使用生产 dist bundle，不是静态 UI 草图。
唯一例外是 concepts/ 下明确标注的三种结构探索。Report Kit、路径、revision、时间、事件、
调用次数和 tokens 都是原有 visual-fixtures 的合成值，不是实际后端/Agent 成功证据。

本地 `visual-review.mjs --production` 仅绑定 127.0.0.1:4179，拦截全部 /api，拒绝写请求；
`discovery-waiting` 合成场景延迟 6 秒返回拒绝，让真实组件展示等待，并不接受或保存业务写入。
不代理真实后端。未调用付费 AI，未启动真实 Agent，未修改实际项目、数据库或 WorkingCopy。

## 实际执行的检查

| 检查 | 结果 / 边界 |
|---|---|
| `pnpm test` | 22 个文件，300 项通过。原有业务回归保留；增加断点隐藏后 body 焦点回退的回归，扩展同阶段手机重选断言 |
| `pnpm typecheck` | 通过 |
| `pnpm build` | 通过；Vue 3 / TypeScript / Vite；未新增依赖 |
| `git diff --check` | 通过 |
| 最终生产 bundle | 实际大小与散列记录于 checks/frontend-verification.txt；没有新的运行依赖 |
| 画面矩阵 | 9 场景 × 5 尺寸，共 45 张实际生产 PNG；1440×900、1280×800、768×900、420×900、320×900 |
| 布局和异常 | 45/45 无整页横向溢出、无记录到的 JavaScript 异常 |
| Chrome AX | 完整矩阵 45 个场景检查无无名控件；产品反馈修订后，15 个受影响非等待场景复查 button/textbox/combobox/link/checkbox/radio 均有名称 |
| 真实计算对比度 | 修正侧栏英文 4.21:1 与旧调试文字 1.57:1；最终采样无可见非装饰正文低于阈值。sr-only 和 aria-hidden 顺序数字单独排除 |
| 同阶段导航 | 420/768 重选当前阶段关闭菜单并聚焦 main；桌面/手机断点将焦点交回可见菜单或品牌 |
| 确认对话框 | 方向候选选择打开原生 dialog，焦点在框内，Escape 关闭并返回原选择按钮；没有确认业务写入 |
| 键盘入口 | Tab 到可见 skip-link，Enter 到主内容；焦点边框可见 |
| 200% 文字 | 九个场景逐元素双倍 computed font-size，均无 document 横向溢出；这是文字放大，不是浏览器菜单缩放 |
| 长来源 | 769 字符不间断技术引用完整显示，无手机横向溢出 |
| 大量 Evidence | 25 条 / 5 页全部访问，逐页展开每条；610–611 字符完整来源，分页后焦点回到状态文本，均无溢出 |
| 减少动态效果 | 模拟真实 media preference；无限动画 0，当前活动文字保留 |
| 对话思考反馈 | 合成场景通过实际输入与提交进入等待；普通模式有 3 个三点动画，减少动态效果模式动画 0，文字提示保留 |
| 边界状态 | empty / deleted 恢复入口 / HTTP503，真实组件在 420 宽渲染并保存截图 |
| 源 detector | 实际安装 Impeccable CLI 返回 `[]`；这不是设计质量结论 |
| Taste 浏览器检查 | 四个重点页面使用安装的 inspect --app；发现小字后修正，执行页复查 0 FAIL / 1 WARN；产品反馈后的时间线在 1440/420/320 复查同样为 0 FAIL / 1 WARN |

Taste 的 ragged-row 警告表示活动/配置或计划概览/准备区高度不同。保留各自自然高度，避免把短内容
拉成空面板；这些区域服务不同任务，不是等价统计卡。较早其它页面的 tabular / ragged-row 和一个
320px blank-gap 属于启发式提示，未通过隐藏内容或宽泛 ignore 消除。未宣称所有 detector 全绿。

## 图像格式与独立可读性

已查明旧工具 `Page.screenshot` 默认 `format='jpeg'`，即使 path 后缀是 .png。V1 旧目录不重写；
新画廊把用于比较的 18 张 V1 图按原始字节复制为正确 .jpg。V2 与结构探索、边界图捕获显式
`format:'png'`。`checks/image-manifest.json` 逐文件记录魔数、真实格式、扩展名一致性、大小和 SHA-256。

最终包内 80 张图像逐一检查签名与扩展名（45 V2、18 V1、6 概念、11 补充/等待状态图）。
Chrome 实际解码 80/80，画廊损坏图片 0、缺失链接 0，桌面和手机画廊均无横向溢出。
浏览器检查完整 PNG/JPEG 解码、画廊引用及本地链接；连接器 `delveforge_read_image` 已实际读取
V2 项目、时间线、思考中截图和 V1 正确后缀的 JPEG，分别返回 `image/png` / `image/jpeg` 图像内容，不是仅列出路径。
这些证据支持本地 Chrome 与本次连接器调用可读，不保证任何第三方查看器或未来连接器都支持。

## 独立审查与限制

[CRITIQUE.md](CRITIQUE.md) 记录隔离 A/B 评估、首轮问题、集中修正与 A 的最后有界确认。
没有把首轮 26/40 或 14/20 改写成最终分数，也没有用自动检查替代用户验收。
产品负责人随后对三处交互提出偏好，最终采用交互气泡、连续时间线和事件优先/结果底置。
再次隔离复查覆盖受影响页面及真实等待，共 18 个状态（1440/420/320），未发现溢出、采样正文
对比度失败或运行异常；等待显示 3 个动点，减少动态效果后为 0。正常等待误显示恢复条的问题
已修复并补充回归；独立测量的具体资产版本与范围保留在临时审计报告中，不宣称真实 Provider 成功。

未执行真实后端/Provider E2E、实际付费运行、NVDA 完整读屏、物理触屏、浏览器菜单缩放、
真实网络条件或 Web Vitals 性能分析。768 与手机是 Chrome 模拟尺寸，不是实体设备。
没有为此扩大 backend Maven 检查范围。技术检查通过与审美接受分别判断。

两个受保护目录的全部 8 个既有文件，前后 SHA-256 清单一致；未覆盖、移动、暂存或提交它们。
审阅用进程在最终交接前停止；没有留下部署服务。

## 重现

```powershell
cd E:\develop\DelveForge\frontend
pnpm test
pnpm typecheck
pnpm build
cd ..
node frontend/scripts/visual-review.mjs --production
# http://127.0.0.1:4179/?visual=projects
```

其它场景：discovery / review / repository / directions / planning / evolution / candidate /
delivery / empty / deleted / error / stress。Ctrl+C 停止。此 dedicated origin 只保存合成夹具身份，
`discovery-waiting` 用于输入并提交一段合成文字，检查拒绝响应返回前的真实等待状态。
不要把该服务当作实际后端界面。最终捕获、格式清单和可打开画廊保存在本目录；临时 Chrome
profile、工具报告和日志在 ignored target/frontend-experience-v2-20261031/。

工具实际来源是安装的 Taste 2.1.5 与 Impeccable 4.8.0。没有 init、root PRODUCT/DESIGN 或自动 hooks。
context 识别 docs/PRODUCT.md；其 legacy schema 提示没有被当作改写项目产品文档的授权。
