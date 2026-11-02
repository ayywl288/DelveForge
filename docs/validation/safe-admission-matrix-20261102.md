# 安全仓库接入：26 样本前后矩阵

旧列来自 353920a 的历史证据；NOT_RUN 不表示通过。新列使用最终 v6 政策。完整 Git 仅证明接入，元数据识别不证明安装、构建或交付。完整固定版本见 [结构化结果](safe-admission-results-20261102.json)。

| 仓库 | 旧完整 Git | 新完整 Git | 资源：公开只读 / 仅主机保留 | 新阶段结果或剩余边界 |
| --- | --- | --- | --- | --- |
| spring-projects/spring-petclinic | COMPATIBLE | COMPATIBLE | 0 / 7 | 隔离准备成功；JDK21 Maven 依赖准备超时，无检查结果；推荐JDK17同样超时，最后等待checkstyle插件依赖下载 |
| spring-projects/spring-petclinic-rest | NOT_RUN | NOT_RUN | 未检查内容 | PIN_FAILED；保留在分母 |
| spring-guides/gs-serving-web-content | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；未评估完整 Git/安装/检查 |
| spring-guides/gs-rest-service | NOT_RUN | BLOCKED | 0 / 4 | MAVEN_ROOT_AMBIGUOUS |
| apache/commons-lang | NOT_RUN | BLOCKED | 0 / 4 | MAVEN_PROFILE_AMBIGUOUS |
| jhy/jsoup | BLOCKED | BLOCKED | 1 / 5 | MAVEN_PROFILE_AMBIGUOUS |
| gunnarmorling/1brc | NOT_RUN | BLOCKED | 0 / 2 | MAVEN_PROFILE_AMBIGUOUS |
| xerial/sqlite-jdbc | NOT_RUN | BLOCKED | 0 / 17 | MAVEN_TOOLCHAIN_UNSUPPORTED |
| linlinjava/litemall | NOT_RUN | COMPATIBLE | 5 / 17 | 接入成功；未实际检查，不推断可构建 |
| YunaiV/ruoyi-vue-pro | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；未评估完整 Git/安装/检查 |
| dromara/RuoYi-Vue-Plus | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；未评估完整 Git/安装/检查 |
| dromara/sa-token | NOT_RUN | BLOCKED | 0 / 112 | MAVEN_MODULE_LIMIT |
| CorentinTh/it-tools | COMPATIBLE | COMPATIBLE | 0 / 6 | 接入成功；未实际检查，不推断可构建 |
| un-pany/v3-admin-vite | NOT_RUN | BLOCKED | 3 / 7 | GIT_TYPE_UNSUPPORTED |
| pure-admin/vue-pure-admin | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Only standalone pnpm configuration is supported; workspace packages/settings are unsupported |
| kouts/vue3-ts-vite-starter-template | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Only standalone pnpm configuration is supported; workspace packages/settings are unsupported |
| lyqht/mini-qr | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Observed .npmrc content unavailable in metadata projection; full content policy required |
| bastienwirtz/homer | COMPATIBLE | COMPATIBLE | 0 / 9 | pnpm BUILD_PASSED_NOT_TESTED；类型/测试脚本缺失；零测试 |
| VueTorrent/VueTorrent | BLOCKED | COMPATIBLE | 1 / 19 | npm 元数据；两份源码被保留，三项目标 CHECK_NOT_AVAILABLE |
| imsyy/SPlayer | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Observed .npmrc content unavailable in metadata projection; full content policy required |
| zyronon/TypeWords | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Declared build script is outside the controlled tool combinations；Declared test script is outside the controlled tool combinations |
| yugasun/vue-ts-starter | NOT_RUN | BLOCKED | 1 / 2 | FRONTEND_CONFIGURATION |
| epicmaxco/vuestic-admin | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Unsupported package manager: observed yarn.lock; no npm/pnpm lock |
| armour/vue-typescript-admin-template | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Outside Vue 3/Vite framework scope; no compatibility conclusion for other frameworks |
| apache/dolphinscheduler | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；GIT_TYPE_UNSUPPORTED；Only pnpm v9 standalone lockfiles are supported |
| alibaba/spring-ai-alibaba | NOT_RUN | NOT_RUN | 未检查内容 | 仅元数据；Outside Vue 3/Vite framework scope; no compatibility conclusion for other frameworks |

完整 Git 子集为最初 11 个加原分母内 1brc/sqlite-jdbc 两个 Maven 诊断样本；后两者在 Petclinic 超时后增加，两者拒绝均保留。
新政策保留样本为 VueTorrent、vue-ts-starter、sa-token：它们在此前任务已被检查，不能称为从未见过；本轮未为其调优内容能力政策。
Homer 开发样本发现 Fetch credentials 枚举/JS 反引号模板引用的误判后做了通用修正。已知示例口令、mock/test 路径没有豁免。
symlink/submodule 仍拒绝：安全解析需要独立链接身份、目标权限和快照/重放规则，本轮没有静默解引用。
13 个原仓库最终 HEAD 与干净状态均与初始一致；三个动态探针额外逐文件 SHA-256 核对原库与正式准备副本：232/132/422 文件不变，私有执行资源清理成功。
