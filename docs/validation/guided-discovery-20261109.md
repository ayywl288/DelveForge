# 引导式需求探索验证

2026-11-09。基线为本地 main 的 `59969743791361ef7c6609a05bcb1027736ed4b4`。以下用例使用确定性提案、本地 SQLite 和离线 HTTP 响应；浏览器截图来自实际 Vue 页面与合成数据。

## 对话与提交

普通输入先做事实提取，再由规划阶段给出回复、上一问结果、下一步和充分性建议。无事实的控制输入可沿用原有快捷路径。正常探索用 `ASK` 提出一个具体问题；解释原问用 `KEEP_PENDING`；暂停或首次审阅选择用 `PAUSE`。模型无法指定消息 ID。服务端使用当前画像唯一的待答问题校验结果；历史问题的字面及近似重复、跳过维度、当前版本充分性和最低缺口均参与校验。无效规划最多修正一次；网络失败直接结束请求。失败轮只保留请求状态，不能向聊天或画像写入半轮结果。

V29 复制旧消息及其序号、时间和外键，持久化 `REPLACED` 并建立唯一待答索引。原有结果 JSON 仍可读取；新结果包含接受的动作。用户主动换目标或需要容易些的入口时可以替换原问；只有明确跳过才记作 `DEFERRED`。申请、取消、恢复、同版冲突检查和最终原子提交沿用原有请求身份。

首次满足审阅条件、没有仍有效的待答问题时，接受的助手消息显示“进入画像审阅”和“继续探索”。用户继续输入或主动索取问题，成功后记录继续选择。审阅仍走已有的领域检查。审阅后返回探索沿用同一周期；已确认画像显式重开视作新周期。侧栏使用当前版本的充分性。

## 上下文和预算

两个阶段分别使用固定的 System 指令、真实角色的已接受历史以及最后一条动态 USER 消息。历史正文只取原始输入、回复和实际呈现的问题，不包含可变的生命周期状态或模型私有推理。提取和规划共享同一个离线 DeepSeek 分词器实例，各自渲染对应思考模式与生成前缀。

| 阶段 | 思考模式 | 实际 max_tokens | 可用输入 | 90% 触发点 | 75% 目标 |
| --- | --- | ---: | ---: | ---: | ---: |
| 事实提取 | Disabled | 8,192 | 991,808 | 892,628 | 743,856 |
| 对话规划 | Low | 16,384 | 983,616 | 885,255 | 737,712 |

默认窗口为 1,000,000 token，请求体上限为 8 MiB。仅删除最早的完整请求组，保留 System、当前材料和输入。固定材料本身超过可用输入或字节上限时，在发送前失败。HTTP 测试核对实际序列化内容和输出预留值。将字节上限缩小到 18,000 的 SQLite + HTTP 用例中，15 个历史轮次在提取请求保留 2 个、规划请求保留 1 个；生成一次新对话后，数据库仍能读出全部 32 条消息。缩小 token 预算的独立用例保留 2 个轮次，估算 98 tokens，低于 112 的目标。

## 回归证据

| 范围 | 验证内容 |
| --- | --- |
| 提问与主题 | 日常主动提问、回答后关掉上一问、解释时保持 ASKED、不知道时换小问题、换目标后 REPLACED、拒答维度跨重启保留、暂停后仍可恢复原问。 |
| 校验与原子性 | 重复旧问题只修正规划一次；连续错误后原问题、画像和 Evidence 保持原状。SQLite 注入提交失败回滚整轮。主动索取已有问题不会再次调用模型。 |
| 审阅 | 首次邀请绑定原回复，按按钮或直接输入均可继续；修订版本不重复邀请，实质纠正使旧充分性失效。进入审阅及确认仍要求显式操作，显式重开新周期可再次邀请。 |
| 历史和恢复 | 两阶段收到同一份有序角色历史，后来回答问题不改变曾发送的正文；旧数据库迁移、分页、适配器及 Spring 重启、重复请求、并发、取消、迟到提交和丢失响应均有覆盖。 |
| Provider | 工具为空的 Disabled / Low 参考计数覆盖 0 至 12 个历史轮次及生成前缀；token 与 HTTP 字节截断保留完整后缀，固定材料超限不发请求。普通刷新不会调用 AI。 |

主要实现和回归入口：

- Application：`DiscoveryConversationService`、`RunUserDiscoveryTurnUseCase`、`ProfileExtraction`、`ProfileSufficiencyEvaluator`、提案解析器与类型、`DiscoveryContext`、请求及持久化端口。
- Infrastructure 和 App：`SqliteDiscoveryConversationRepository`、V29、`DeepSeekAiGatewayAdapter`、分词器装配、配置与数值诊断、Discovery HTTP DTO 和 Controller。
- 前端：`DiscoveryView.vue`、`state/session.ts`、`api/types.ts`、`workflows-v2.css` 和对应场景夹具。沿用原有 API 路径及请求身份。
- 自动回归：`DiscoveryConversationIntegrationTest`、`UserProfileDiscoveryTurnEndpointIntegrationTest`、`DeepSeekAiGatewayAdapterTest`、`DeepSeekTokenCounterAdapterTest` 与 `discovery-messaging.test.ts`。

## 浏览器实查

在 Windows Chrome 的独立配置中打开生产构建的合成数据页面。桌面 1440×960 保留左侧对话和右侧画像，390×844 使用单列；窄屏截图滚动至对应交互。两种宽度的页面均未出现横向溢出。追问紧跟回复并以字重区分；保留原问时输入区上方能读到确切问题；两种宽度都用 Tab 从审阅按钮移至继续探索，焦点环可见。失败场景中页面显示结果待核对，未展示成功的助手回复。

| 场景 | 桌面 | 窄屏 |
| --- | --- | --- |
| 主动提问 | [1440](guided-discovery-20261109/discovery-1440.png) | [390](guided-discovery-20261109/discovery-390.png) |
| 保留原问 | [1440](guided-discovery-20261109/discovery-pending-1440.png) | [390](guided-discovery-20261109/discovery-pending-390.png) |
| 首次审阅邀请 | [1440](guided-discovery-20261109/discovery-invitation-1440.png) | [390](guided-discovery-20261109/discovery-invitation-390.png) |
| 继续探索 | [1440](guided-discovery-20261109/discovery-continued-1440.png) | [390](guided-discovery-20261109/discovery-continued-390.png) |
| 等待提交 | [1440](guided-discovery-20261109/discovery-waiting-1440.png) | [390](guided-discovery-20261109/discovery-waiting-390.png) |
| 错误与结果核对 | [1440](guided-discovery-20261109/discovery-error-1440.png) | [390](guided-discovery-20261109/discovery-error-390.png) |

## 执行与边界

Windows / Java 21 运行 Maven Wrapper 离线全量 verify 并成功打包。模块分别运行 Domain 209、Application 929、Infrastructure 636、App 229 项，其中后两者各跳过 52、3 项。合计 2,003 项，1,948 通过，0 失败、0 错误、55 跳过。全量编译后补充的上下文处理、历史迁移断言与暂停用例由最终源码的定向验证单独覆盖。

前端运行项目的 pnpm test、typecheck、build。构建及类型检查通过，23 个测试文件共 370 项通过，0 失败、0 跳过。离线最终源码定向 Maven verify 覆盖 Discovery、充分性、DeepSeek Gateway 和分词器回归，并完成应用打包：276 项通过，0 失败、0 错误、0 跳过。各次验证独立计数。

未调用付费模型。真实多轮措辞质量、跨话题语义判断和实际服务的前缀缓存命中率尚未测量；本地近似文本比较不能识别所有同义改写。
