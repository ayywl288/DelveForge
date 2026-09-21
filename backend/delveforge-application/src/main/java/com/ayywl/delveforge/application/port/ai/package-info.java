/**
 * AI Gateway 抽象。
 *
 * <p>业务模块只能依赖本包的接口访问 LLM，不得直接依赖具体 Provider SDK
 * （RULE-ARCH-008）。Provider 的 Model ID、Endpoint、凭据与参数属于配置与
 * Infrastructure，不得硬编码进业务逻辑。
 *
 * <p>本包只定义“模型如何产生语义候选”，不定义“什么样的结果构成合法领域状态”。
 * 返回内容必须经 Application / Domain 校验后才能成为领域状态（RULE-DOM-003）。
 *
 * <p>本包还包含 {@code AiJsonObjectReader}：它不是 Port，但定义的是 AI 边界本身的一条契约
 * ——{@code AiGateway} 返回的原始内容必须是「恰好一个 json 对象、其后没有多余内容」。
 * 各业务流程的解析器共用它，因此它归属这里，而不是某一个业务流程的包。
 */
package com.ayywl.delveforge.application.port.ai;
