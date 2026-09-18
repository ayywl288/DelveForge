package com.ayywl.delveforge.app.error;

/**
 * 对外错误分类。
 *
 * <p>HTTP Status 仍然负责表达协议层错误类别，本枚举只提供更稳定、机器可读的
 * 应用层分类，不替代 HTTP Status。
 *
 * <p>当前取值与 {@link ApiExceptionHandler} 的处理分支一一对应，
 * 不构成完整的 Error Code 体系；后续只有出现真实需要区分的新失败类别时才扩展。
 */
public enum ApiErrorCode {

    /** 请求本身不合法，调用方可以修正后重试。 */
    INVALID_REQUEST,

    /** 请求指向的领域对象不存在。 */
    NOT_FOUND,

    /** 请求与领域对象当前状态冲突，调整时机或状态后可重试。 */
    CONFLICT,

    /** DelveForge 依赖的外部能力当前不可用。 */
    EXTERNAL_CAPABILITY_UNAVAILABLE,

    /** 服务内部错误，具体原因只记录在服务端日志中。 */
    INTERNAL_ERROR
}
