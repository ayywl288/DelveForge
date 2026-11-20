# ADR-0030 工作台的模型配置与协议重放

工作台允许在同一 Conversation 的两次 Run 之间选择具有工具调用能力的模型。ADR-0028 和 ADR-0029 继续规定工具准入、执行、项目隔离和恢复。原流程的退役与数据保留见 [ADR-0031](0031-retire-workflow-runtime.md)。

## 集成边界

Infrastructure 通过 Spring AI BOM 2.1.0-RC2 和 Spring Boot 4.2.0-RC2 集成供应商。稳定版 Spring AI 2.0.2 缺少本次需要的有序部件 API。RC 的协议升级需重新运行八家服务的 HTTP 合同测试。SQLite 的已有 JSON 字段继续用 Jackson 2 读取，HTTP 接口和 Spring AI 部件使用 Jackson 3。Spring JDBC 和 Flyway 管理控制数据库。

控制端调用原生 ChatModel。工具回调只提供原有 JSON Schema，调用回调本身会失败。只有 DelveForge 能准入和执行工具。OpenAI 使用无服务端会话的 Responses，Anthropic 使用 Messages，Google 使用 GenAI，DeepSeek 使用原生模型。智谱、百炼、Kimi 和 OpenRouter 共用 Chat Completions。SDK 的提供方重试关闭，每次实际发送独立取得 Run 请求额度。固定端点上的请求禁用重定向。OpenRouter 的 reasoning_details、Gemini 可选回执 ID 与 DeepSeek low 是 RC2 需要的窄适配。

## 选择、容量与历史

对话偏好只保存提供方、模型和思考设置。V8 在创建排队 Run 的事务中冻结偏好、官方端点预设、工作空间、凭据版本引用、协议、上下文容量、输出预留和能力版本。SQLite 触发器防止修改已有快照。暂停后继续沿用原快照。用户可结束暂停任务，尚未配对的工具须先核对。旧数据库中未结束的 Run 首次升级时冻结当时可得的历史环境配置，升级以前没有记录过的凭据版本无法还原。

模型目录初次读取采用本机缓存和内置目录。浏览模型时在后台刷新 Models.dev 的公开数据。区域及 Coding Plan 选择相应的模型清单。工具能力和输入输出容量来自元数据，实际账户的权限通过两轮测试调用确认。提供方默认以外的思考值按有文档依据的模型映射。

上下文沿用现有的完整工具批次边界、用户原文保留和检查点规则。Run 的窗口与输出预算取模型容量和宿主上限，Spring AI 的通用分词估计包含工具描述和有序部件并增加 25% 余量。提供方拒绝上下文时暂停，原始历史仍可读取。

2026-11-17 的校准发现短工具请求有固定低估，DeepSeek 和智谱的请求估算据此补计一次工具开销。[校准记录](../WORKBENCH_STABILIZATION.md#c-输入-token-校准)

私有记录保存 Spring AI 的 MessagePart 序列，包括属性、UnknownPart 和 OpaquePayload。真实回执的顺序与 ID 由现有记录保管。Gemini 没有提供 ID 时只为本地回执分配关联号。原先按可重放后缀裁剪历史的决定已由 [ADR-0032](0032-cross-model-history.md) 更新。旧 DeepSeek reasoning 字段可兼容读取。浏览器、历史查询工具与模型错误诊断只取得公开投影。

## 凭据

Windows 使用当前用户的 DPAPI。其他宿主可用独立提供的 256 位主密钥和 AES-GCM，或者使用提供方环境变量。保护范围绑定提供方、区域、工作空间与凭据版本。替换或删除凭据会使旧 Run 的引用失效。数据库只保存密文和引用。密钥留在宿主控制侧，工作副本、开发容器与浏览器存储均无需取得密钥。

框架接口依据 [Spring AI 消息部件](https://spring.io/blog/2026/10/28/spring-ai-message-parts-reasoning-replay/) 与 [ChatModel 工具协议](https://docs.spring.io/spring-ai/reference/2.1/api/tools/chatmodel-tool-calling.html)。模型目录来自 [Models.dev](https://models.dev/)。
