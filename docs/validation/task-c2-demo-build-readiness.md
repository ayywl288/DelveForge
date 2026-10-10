# Task C2 — 三个演示仓库的构建基线

2026-10-11。本阶段为 Task C 准备独立 Java/Maven 输入，不修改 DelveForge 业务实现，
不调用 Provider。三套源码位于 `E:\df-demo-pack\20261011\repositories`，不进入主仓库。
验证级别均为 **BUILD_READY**：构建及已有/新增测试通过，尚未进行 AI 演化。

## 固定来源与许可

| 演示 | 固定上游 revision | 许可及实际取材 | 生产 Java 规模 |
|---|---|---|---|
| [ExpenseTracker](https://github.com/akshitap30/ExpenseTracker/tree/d1b1678caef82f1ae034daaf0f4986814ff5a2f1) | `d1b1678caef82f1ae034daaf0f4986814ff5a2f1` | 根 `LICENSE` 为 MIT；保留 Akshita Pardeshi 的署名及原许可 | 26 文件 / 2268 行 |
| [TodoList API](https://github.com/EddyPBR/todolist-api/tree/15353cdd6fd7b3f743e9ed47ee5a4f5152339ff5) | `15353cdd6fd7b3f743e9ed47ee5a4f5152339ff5` | 根 `LICENSE` 为 MIT，原文署名 Scott Chacon and others；原样保留，不另造作者归属 | 13 文件 / 446 行 |
| [Spring REST Guide](https://github.com/spring-guides/gs-rest-service/tree/3f4cef01152596c19bc4d37939409358812b1421) | `3f4cef01152596c19bc4d37939409358812b1421` | `LICENSE.txt` 为 Apache-2.0；仅提取 `complete` Maven 工程。`LICENSE.writing.txt` 原样保留，不复制或改写其 BY-ND 教程正文 | 3 文件 / 35 行 |

三个优先候选均可用，没有替换仓库。先用不 checkout 的 Git clone 检查固定 commit，
再从 archive 提取；校验解压目标位于指定目录内。每套演示的第一个独立 commit 保存原始
导入，第二个 commit 保存适配。它们不是上游完整历史；`ORIGIN.json` 保留固定上游及导入
revision，后续 Bundle 包含这两个本地提交，可直接检查适配 diff。

## 构建前检查及适配

检查 POM、Wrapper、插件、脚本、外部调用及测试入口后才执行构建。三个 POM 的显式
构建插件均为 Spring Boot 插件，继承正常的 compiler / surefire 配置；没有执行 Docker、
CI、Gradle 或上游 `test/run.sh`。统一采用已审查的 Apache only-script Wrapper 3.3.4 / Maven
3.9.14，脚本保留许可及变更注记，MIT 项目另附 `MAVEN-WRAPPER-LICENSE.txt`。TodoList 原有
wrapper.jar 文件保留，但当前启动脚本不执行它。

改动仅为构建、测试、演示配置、来源文档与仓库内换行/忽略规则，**没有修改任何
`src/main/java` 文件**，没有提前实现演化建议，也没有改变共享 Git 配置。

| 仓库 | 实际基础设施适配 | 新增的真实断言 |
|---|---|---|
| ExpenseTracker | 保留 Spring Boot 3.5.3 / Java 21；已有 H2 依赖从 test 调为 runtime，供显式 demo profile 使用；测试上传路径改到 target，明确 H2 方言；demo profile 使用内存库和 loopback，需要环境变量 `DEMO_JWT_SECRET` | 3 个服务/JPA 集成测试：CRUD 值真实持久化；分类、大小写不敏感搜索、分页保持用户范围；另一用户不能读取、更新、删除。邮件和文件存储使用测试替身，数据访问为真实 H2/JPA |
| TodoList API | 保留 Spring Boot 3.0.11 / Java 编译目标 17，在 JDK 21 运行；测试使用 H2 create-drop、关闭 H2 console；替换旧 Wrapper 启动方式 | 3 个 MockMvc/H2 集成测试：实际 Basic/BCrypt 过滤器下的用户 CRUD；未认证与其它用户请求被拒绝且数据不变；过去/倒置日期拒绝且不持久化。设置 servletPath 以实际经过上游过滤器 |
| Spring REST | 保留 Spring Boot 4.0.8 / Java 编译目标 17；提取 Maven 工程，不包含 initial / Kotlin / Gradle | 保留两个真实 HTTP 测试；新增问候 ID 递增和 Unicode 姓名不变的测试 |

TodoList 第一次尝试因本任务新增测试的 JSON 字符串转义错误，在 testCompile 失败，尚未
进入测试执行。改用已有 ObjectMapper 序列化 `Map` 后通过，断言没有删减；该失败不是上游
业务缺陷，日志保留为 `evidence/todolist-api-first-compile-failure.log`。

## 独立基线与实际验证

运行环境为 Windows / Oracle JDK **21.0.12.1**。适配提交后分别再次运行根 Wrapper `verify`：

| 仓库 | 原始导入 commit | BUILD_READY baselineRevision | 结果 |
|---|---|---|---|
| ExpenseTracker | `f20410398d5b47ab3694c1b86d18ccd637894813` | `afb14c051daa5a5cf00ebc7a2257a887fdaa6194` | BUILD SUCCESS；12 测试，12.987 秒 |
| TodoList API | `ceb3b9d2c8973e320cc57bf0e670cd43c6594212` | `855efb07140430fd0029b76f754623c6480ad1ab` | BUILD SUCCESS；4 测试，12.194 秒 |
| Spring REST | `39a69b411c8b0c352869f7efa5138f49d05f43ee` | `23a63bf22bcc689d6a41a003b7abb3391ab68693` | BUILD SUCCESS；3 测试，6.451 秒 |

失败、错误、跳过均为 0。计数取自真实 Surefire XML，不是模型描述。上表为依赖已下载后的
验证耗时，不包含第一次下载。构建后的 Git 工作树均干净，target 被各仓库忽略。

**C3 后续还原检查：** TodoList 与 Spring REST 新增的强制 POM LF 规则与原 CRLF blob
不匹配，导致 fresh clone 显示 POM 修改。去掉规则、保留原 POM 后追加独立适配提交并通过
还原验证；上表保留 C2 当时的验证 revision，最终 Bundle baseline 以
[manifest](../demos/manifest.json) 为准，过程见 [C3](task-c3-demo-bundle-delivery.md)。

证据根为 `E:\df-demo-pack\20261011`：

- `upstream/`：原始 clone 及固定 commit 的 zip 导出，保持只读来源。
- `repositories/<demo>/ORIGIN.json`、`DEMO.md`、许可证：来源、适配与限制。
- `evidence/<demo>-verify.log`：首次成功构建；TodoList 初次编译失败另存。
- `evidence/<demo>-baseline-verify.log`：适配 commit 对应的最终 C2 验证。
- `repositories/<demo>/target/surefire-reports/TEST-*.xml`：实际断言执行证据。

## 尚未实现的演化方向

| 演示 | 方向一 | 方向二 |
|---|---|---|
| ExpenseTracker | 每月分类预算与超限提示 | 周期性支出规则与生成记录 |
| TodoList API | 完成状态及已完成/未完成筛选 | 日期范围及优先级筛选 |
| Spring REST | 问候语言参数及支持语言校验 | 有界批量问候接口 |

这是根据固定源码提出的人工建议，不是 Provider 已生成或验证的 ProductDirection。
TodoList README 宣称的完成/分类能力未出现在实际 TaskModel 中，因此不能当作已有功能。

## 使用边界

自动化测试无需外部数据库或邮件服务；首次运行仍需联网下载 Maven/依赖，不承诺空缓存
离线构建。ExpenseTracker 的真实注册邮件仍需要 SMTP，demo profile 没有模拟该生产流程。
TodoList 上游实体直接作为请求/响应，可能暴露密码哈希，更新字段未采用 DTO 白名单；本次
不扩大为安全重构。只使用合成数据，若手动启动示例服务，限制在 loopback。

审查过构建内容不等于 OS 隔离。Windows Maven 插件/测试运行于宿主权限，测试通过也不是
生产安全认证。C2 没有生成 CodingProposal、Candidate 或 Verified B；跨仓库真实 Provider
效果仍需用户单独手动验收。C3 将另行验证 Bundle 还原与构建，不把还原成功当作 AI 演化。
