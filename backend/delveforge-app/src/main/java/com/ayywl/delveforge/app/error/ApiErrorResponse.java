package com.ayywl.delveforge.app.error;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * <p>只承载诊断所需的最小信息，不携带内部异常类型、堆栈或外部能力返回的原始内容。
 *
 * @param code      应用层错误分类
 * @param message   面向调用方的说明，不包含内部实现细节
 * @param path      出错的请求路径，便于与日志对照
 * @param timestamp 错误发生时间
 */
public record ApiErrorResponse(ApiErrorCode code, String message, String path, Instant timestamp) {
}
