/**
 * Interface 层统一错误映射。
 *
 * <p>负责把 Application / Infrastructure 暴露出的异常翻译为对外响应，
 * 是错误分类与错误响应构造的唯一位置（RULE-ARCH-005）。
 *
 * <pre>
 * 异常来源                                    HTTP     ApiErrorCode
 * IllegalArgumentException                   400      INVALID_REQUEST
 * AiGatewayException                         502      EXTERNAL_CAPABILITY_UNAVAILABLE
 * WorkspaceException                         500      INTERNAL_ERROR
 * Spring MVC 协议层异常                        由框架决定  INVALID_REQUEST / INTERNAL_ERROR
 *   （405 Method Not Allowed、415、400 请求体无法解析等）
 * 其余未预期异常                               500      INTERNAL_ERROR
 * </pre>
 *
 * <p>协议层异常继承自 {@code ResponseEntityExceptionHandler}，状态码与响应头
 * （405 的 {@code Allow}、415 的 {@code Accept}）由框架确定，
 * 本包只覆盖 {@code handleExceptionInternal} 替换响应体。
 * 自行重建响应会丢失这些协议头。
 *
 * <h2>响应体不含异常文本</h2>
 *
 * <p>异常 message、堆栈、原因链以及框架生成的细节文本都可能包含请求内容、
 * 用户数据或第三方 SDK 的原始返回，因此一律不进入响应体。
 * 响应只给出稳定的错误分类与固定文案，详细信息写入服务端日志。
 *
 * <p>当前尚未建立「明确可公开的校验信息」类型。M1 引入真实输入校验时，
 * 应由 Application 层抛出可公开的专用异常并在此新增映射，
 * 而不是把任意异常文本回传给调用方。
 *
 * <p>Controller 不得自行构造错误响应，也不得捕获异常后返回业务语义的成功结果。
 *
 * <h2>日志</h2>
 *
 * <p>格式为 {@code operation=... path=... result=... exception=...}，
 * 其中 {@code exception} 是异常类型链与堆栈位置。
 *
 * <p>异常 message 与原因链文本<b>任何级别都不记录</b>：
 * 它们可能嵌入请求内容、用户数据或第三方 SDK 原始返回中的凭据，
 * 且无法在记录前可靠判定。类型链与堆栈位置是代码标识，不承载运行时数据。
 * {@code AGENTS.md} §8.8 禁止记录凭据的规则不区分日志级别，DEBUG 不是例外。
 *
 * <p>另有一部分框架日志发生在本包之外、无法拦截，
 * 由 {@code application.yml} 中固定的框架包级别处理，见该文件注释。
 */
package com.ayywl.delveforge.app.error;
