# SWE-bench Pro V2 本机可行性

本轮读取指定版本的四套定义，核对公开镜像的清单与配置。没有下载这四个镜像层，也没有在本机执行 Pro 的 No-op、Oracle 或 Agent。上游的发布验证是外部证据。

四份 Dockerfile 均以标签引用 GHCR。下表对应的摘要来自本轮匿名读取的 registry 清单，并已核对镜像配置为 linux/amd64；尚未在容器内核对 Git HEAD。在将任何补丁交给 verifier 前，须另行核对 DelveForge 导入的基线与该镜像中的仓库树一致。

来源：[V2 协议与发布检查](https://github.com/scaleapi/SWE-bench_Pro-os/blob/66f92766bba642462d4bbe5479e83f91f9211862/v2/README.md)。

| 候选 | 实际能力 | 参考文件 / 增删行 | F2P / P2P | 镜像压缩 GiB | 建议 |
|---|---|---:|---:|---:|---|
| NodeBB | 权限与跨入口功能 | 9 / +37 −7 | 1 / 148 | 0.78 | 暂缓；若建设独立验证器，此题跨模块价值高。 |
| Flipt | 存储与缓存功能 | 4 / +44 −19 | 1 / 1 | 1.66 | 暂缓；未来可优先用作窄适配器验证样例。 |
| Navidrome | 播放列表数据模型功能 | 10 / +132 −48 | 1 / 0 | 0.88 | 暂缓；若目标是复杂跨模块交付，优先级低于另外三题。 |
| OpenLibrary | 跨格式数据兼容修复 | 5 / +73 −70 | 2 / 57 | 0.83 | 暂缓；跨模块、双格式一致性有独立价值。 |

### NodeBB

[固定任务定义](https://github.com/scaleapi/SWE-bench_Pro-os/tree/66f92766bba642462d4bbe5479e83f91f9211862/v2/tasks/instance_NodeBB__NodeBB-0e07f3c9bace416cbab078a30eae972868c0a8a3-vf2cf3cbd463b7ad942381f1c6d077626485a1e9e)　`instance_NodeBB__NodeBB-0e07f3c9bace416cbab078a30eae972868c0a8a3-vf2cf3cbd463b7ad942381f1c6d077626485a1e9e`

配置保留标签；创建、编辑、队列和 Socket 传递操作者身份，特权用户可继续使用。涉及配置、模板、翻译和后端。 Node/Mocha 及容器内 Redis。唯一 F2P 主要覆盖普通用户的标签限制，不能据此断言所有入口都独立受测。

参考文件：`install/data/defaults.json`、`public/language/en-GB/admin/settings/tags.json`、`public/language/en-GB/error.json`、`src/posts/edit.js`、`src/posts/queue.js`、`src/socket.io/topics/tags.js`、`src/topics/create.js`、`src/topics/tags.js`、`src/views/admin/settings/tags.tpl`。

镜像 `ghcr.io/scaleapi/swe-bench_pro-v2:instance_NodeBB__NodeBB-0e07f3c9bace416cbab078a30eae972868c0a8a3-vf2cf3cbd463b7ad942381f1c6d077626485a1e9e@sha256:da6f714a9a8b21c6f3a0e7c8b452178eb9abdfdce789926581ec70d3bb6d87e3`。

### Flipt

[固定任务定义](https://github.com/scaleapi/SWE-bench_Pro-os/tree/66f92766bba642462d4bbe5479e83f91f9211862/v2/tasks/instance_flipt-io__flipt-15b76cada1ef29cfa56b0fba36754be36243dded)　`instance_flipt-io__flipt-15b76cada1ef29cfa56b0fba36754be36243dded`

命中缓存、未命中回源、命名空间与旗标的键顺序及 JSON 省略空值。参考补丁也改了两个 UI 组件。 Go 测试限定 internal/storage/cache；精确缓存键、结构和序列化由题面明示。两项目标范围不证明界面正确或生产性能。

参考文件：`internal/storage/cache/cache.go`、`internal/storage/storage.go`、`ui/src/app/flags/rollouts/Rollouts.tsx`、`ui/src/app/flags/rules/Rules.tsx`。

镜像 `ghcr.io/scaleapi/swe-bench_pro-v2:instance_flipt-io__flipt-15b76cada1ef29cfa56b0fba36754be36243dded@sha256:566f157e478caf813df59bbbbce8fb2d919d8d52f2131065ada26a031eb67829`。

### Navidrome

[固定任务定义](https://github.com/scaleapi/SWE-bench_Pro-os/tree/66f92766bba642462d4bbe5479e83f91f9211862/v2/tasks/instance_navidrome__navidrome-28389fb05e1523564dfc61fa43ed8eb8a10f938c)　`instance_navidrome__navidrome-28389fb05e1523564dfc61fa43ed8eb8a10f938c`

识别 M3U/M3U8 扩展名，按规定格式写播放列表、轨道时长和文件路径。 Go 的 core/model 测试，目标是聚合的 TestModel。参考修改还涉及命令、配置、扫描器等，评分不能证明一个完整 CLI 已交付。

参考文件：`cmd/pls.go`、`conf/configuration.go`、`core/auth/auth.go`、`core/playlists.go`、`log/log.go`、`model/playlist.go`、`scanner/playlist_importer.go`、`scanner/tag_scanner.go`、`scanner/walk_dir_tree.go`、`server/nativeapi/playlists.go`。

镜像 `ghcr.io/scaleapi/swe-bench_pro-v2:instance_navidrome__navidrome-28389fb05e1523564dfc61fa43ed8eb8a10f938c@sha256:a98f571e964885394cc22adf9202b1a66e1c3daf27d34c63098a79ef00e22300`。

### OpenLibrary

[固定任务定义](https://github.com/scaleapi/SWE-bench_Pro-os/tree/66f92766bba642462d4bbe5479e83f91f9211862/v2/tasks/instance_internetarchive__openlibrary-111347e9583372e8ef91c82e0612ea437ae3a9c9-v2d9a6c849c60ed19fd0858ce9e40b7cc8e097e59)　`instance_internetarchive__openlibrary-111347e9583372e8ef91c82e0612ea437ae3a9c9-v2d9a6c849c60ed19fd0858ce9e40b7cc8e097e59`

MARC XML 与二进制记录的 $6 / 880 链接应得到一致作者异名、题名、副标题及其他标题。涉及公共基类、两种解析器和 edition 输出。 Python/pytest，运行所选 MARC 用例与现有解析回归。脚本的全量分支另含集成测试，选定路径不据此要求部署完整网站。

参考文件：`openlibrary/catalog/marc/get_subjects.py`、`openlibrary/catalog/marc/marc_base.py`、`openlibrary/catalog/marc/marc_binary.py`、`openlibrary/catalog/marc/marc_xml.py`、`openlibrary/catalog/marc/parse.py`。

镜像 `ghcr.io/scaleapi/swe-bench_pro-v2:instance_internetarchive__openlibrary-111347e9583372e8ef91c82e0612ea437ae3a9c9-v2d9a6c849c60ed19fd0858ce9e40b7cc8e097e59@sha256:9aff64d45c401ec5b497c25914dfb39de5c6fed96888ffaa6f21e76e285f612e`。

## 本机条件与协议差异

四份 task.toml 均声明 Linux、1 CPU、4096 MB 内存、10240 MB 存储，环境构建上限 1800 秒，Agent 和 verifier 各 3000 秒。Docker Desktop 实报 Linux、32 个逻辑 CPU、约 7.56 GiB 内存，具备串行启动一个此规格环境的基础条件。存储字段是配额需求，表中压缩层大小不等于解压或运行占用。镜像可解析不证明依赖完整；四套服务与本机 Oracle 仍待实测。

DelveForge 的开发环境固定为 2 CPU、3072 MiB、768 PID、45 分钟，并允许列出的包源。V2 的锁定 Agent 离线运行，验证器使用公开网络。四份 instruction.md 均指向 /app；当前产品只挂载 /projects/<ProjectId>。评分容器可使用原生 /app，但这不能解决 Agent 端题面目录差异。即使评分完全一致，预算、网络和 Agent 工作目录仍须在报告中披露。

## 三种路径

| 方案 | 需要承担的工程工作 | 结论 |
|---|---|---|
| A · 独立评分适配器 | 取得并核对题目基线树；复用现有冻结补丁，在全新固定镜像的 /app 做 git apply --check、应用和树校验。仅在验证阶段按官方布局投放 /tests，留出可写 /logs/verifier。执行原 test.sh，保留 reward.txt、解析输出、stdout/stderr、镜像摘要及超时或设施错误。以空补丁和参考补丁各验一次，并核对 F2P/P2P。 | 评分侧是边界明确的改动；Agent 端路径、离线依赖与资源差异另有成本。若单独立项，优先 Flipt。 |
| B · Harbor 只管验证 | DelveForge 仍负责 Project、Run 和冻结；增加桥接以提供 Harbor 的任务目录、补丁或 replay 期望的 source_job、作业标识及结果收集。Harbor 另行创建和销毁干净验证环境，需要实测本地后端与网络、资源映射。 | 生命周期更多。四题不足以证明引入依赖的收益。 |
| C · 暂缓 | 保存任务、镜像身份和验证要求，当前扩展继续沿用 Multilingual 的独立官方评分。 | 本轮选择。未安装 Harbor，未使用 Modal。 |

官方 test.sh 会恢复受保护测试与 fixture、应用官方测试补丁、调用 run_script.sh 和 parser.py，最后写 reward。异常退出也可能写 0，故须同时保留阶段日志以区别答错与设施失败。基线和 verifier 必须隔离，禁止向 Agent 提供 gold、tests 或含答案的 Git 历史。本机可用 Docker 实现免费对照；本轮仅评估，尚无本机实测或排行榜等价性结论。

本机原件：`target/xad/pro/<ID>/`；镜像回执：`target/xad/images/<ID>.json`。元数据和验证器哈希见[候选清单](candidates.json)。
