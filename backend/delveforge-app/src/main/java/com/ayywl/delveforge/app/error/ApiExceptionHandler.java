package com.ayywl.delveforge.app.error;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.userdiscovery.UserProfileNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 统一错误映射。
 *
 * <p>Interface 层在这里把 Application / Infrastructure 暴露出的异常翻译为对外响应。
 * Controller 只负责解析请求与调用 Use Case，不承担异常分类与错误构造。
 *
 * <h2>不泄漏内部信息</h2>
 *
 * <p>响应体只包含稳定的错误分类与固定文案。
 *
 * <h2>日志只记录结构上不含数据的内容</h2>
 *
 * <p>异常的 message 与原因链文本都可能嵌入请求内容、用户数据或第三方 SDK
 * 返回的原始文本（含凭据），且无法在记录前可靠判定其安全性，
 * 因此一律不写入日志（AGENTS.md §8.8 不区分日志级别）。
 *
 * <p>日志记录的是 {@link #describe(Throwable)} 的结果：异常类型链与堆栈位置。
 * 类名、方法名、文件名与行号都是代码标识，不承载运行时数据。
 * 这样既满足「不记录凭据」，也保留定位失败所需的信息。
 *
 * <h2>协议层错误交给框架</h2>
 *
 * <p>继承 {@link ResponseEntityExceptionHandler}，由框架负责判定 405 / 415 / 400
 * 等协议错误的状态码与响应头（例如 405 的 {@code Allow}、415 的 {@code Accept}），
 * 本类只覆盖 {@link #handleExceptionInternal} 把响应体替换为统一形状。
 * 自行重建响应会丢失这些协议头，不要这么做。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 原因链深度上限，避免异常链被异常构造时日志失控。 */
    private static final int MAX_CAUSE_DEPTH = 10;

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static final String INVALID_REQUEST_MESSAGE = "请求内容不合法，详细信息见服务端日志";
    private static final String NOT_FOUND_MESSAGE = "指定的资源不存在";
    private static final String AI_GATEWAY_FAILURE_MESSAGE = "外部 AI 能力调用失败，详细信息见服务端日志";
    private static final String WORKSPACE_FAILURE_MESSAGE = "本地能力调用失败，详细信息见服务端日志";
    private static final String INTERNAL_ERROR_MESSAGE = "服务内部错误，详细信息见服务端日志";

    /** 未匹配到任何路由时，日志中使用的固定路径标识。 */
    private static final String UNMATCHED_ROUTE = "<unmatched>";

    /**
     * 请求参数或请求体不满足 Application / Domain 的输入约束。
     *
     * <p>{@link IllegalArgumentException} 也可能来自 JDK 或第三方库，
     * 其 message 不作为调用方可见文案。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidRequest(IllegalArgumentException exception,
                                                 HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=INVALID_REQUEST exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.INVALID_REQUEST, INVALID_REQUEST_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * 请求指向的领域对象不存在。
     *
     * <p>与 {@link IllegalArgumentException} 区分：调用方的请求形态是合法的，
     * 只是目标当前不存在，属于可预期的业务失败，不是服务端错误。
     */
    @ExceptionHandler(UserProfileNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleNotFound(UserProfileNotFoundException exception,
                                           HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=NOT_FOUND exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * AI Gateway 调用失败：外部 LLM 能力不可用。
     */
    @ExceptionHandler(AiGatewayException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiErrorResponse handleAiGatewayFailure(AiGatewayException exception,
                                                   HttpServletRequest request) {
        log.error("operation=interface.request path={} capability=ai-gateway result=FAILED exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.EXTERNAL_CAPABILITY_UNAVAILABLE,
                AI_GATEWAY_FAILURE_MESSAGE,
                request.getRequestURI(),
                Instant.now());
    }

    /**
     * Workspace 调用失败：本地软件操作（Git / 文件系统 / 构建等）失败。
     *
     * <p>Workspace 是本地能力而非远端服务，因此归入服务端错误。
     */
    @ExceptionHandler(WorkspaceException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleWorkspaceFailure(WorkspaceException exception,
                                                   HttpServletRequest request) {
        log.error("operation=interface.request path={} capability=workspace result=FAILED exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.INTERNAL_ERROR, WORKSPACE_FAILURE_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * Spring MVC 协议层异常（405 / 415 / 400 等）的统一出口。
     *
     * <p>状态码与响应头由框架在调用本方法前已经确定，这里只替换响应体：
     * {@code headers} 必须原样传递，否则会丢失 {@code Allow}、{@code Accept} 等协议头。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        HttpStatus status = HttpStatus.resolve(statusCode.value());
        HttpStatus resolved = (status != null) ? status : HttpStatus.INTERNAL_SERVER_ERROR;

        ApiErrorCode code = resolved.is4xxClientError()
                ? ApiErrorCode.INVALID_REQUEST
                : ApiErrorCode.INTERNAL_ERROR;

        log.warn("operation=interface.request path={} result=PROTOCOL_ERROR status={} exception={}",
                loggedRoute(request), resolved.value(), describe(exception));

        return new ResponseEntity<>(
                new ApiErrorResponse(code, messageFor(code), requestPath(request), Instant.now()),
                headers,
                resolved);
    }

    /**
     * 兜底处理未预期异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception,
                                                             HttpServletRequest request) {
        log.error("operation=interface.request path={} result=UNEXPECTED_ERROR exception={}",
                loggedRoute(request), describe(exception));

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                ApiErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE,
                request.getRequestURI(), Instant.now()));
    }

    /**
     * 渲染异常的安全诊断描述：异常类型链 + 堆栈位置。
     *
     * <p>刻意不包含任何 message 与原因链文本。这些文本可能是调用方输入、
     * 用户数据或第三方 SDK 的原始返回，其中可能带有 API Key / Token 等凭据，
     * 且无法在记录前可靠判定，因此不写入日志（AGENTS.md §8.8）。
     *
     * <p>类型链与堆栈位置足以判断：哪个组件失败、经由哪一层、在哪个位置抛出。
     * 需要更具体的原因时，应在对应 Adapter 中以安全的形式显式记录，
     * 而不是把整条异常文本倾倒进日志。
     */
    private static String describe(Throwable throwable) {
        StringBuilder description = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;

        while (current != null) {
            if (depth == MAX_CAUSE_DEPTH) {
                description.append(LINE_SEPARATOR)
                        .append("... (cause chain truncated after ")
                        .append(MAX_CAUSE_DEPTH)
                        .append(" levels)");
                break;
            }
            if (depth > 0) {
                description.append(LINE_SEPARATOR).append("Caused by: ");
            }
            description.append(current.getClass().getName());
            for (StackTraceElement frame : current.getStackTrace()) {
                description.append(LINE_SEPARATOR).append("\tat ").append(frame);
            }
            current = current.getCause();
            depth++;
        }
        return description.toString();
    }

    private static String messageFor(ApiErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> INVALID_REQUEST_MESSAGE;
            case NOT_FOUND -> NOT_FOUND_MESSAGE;
            case EXTERNAL_CAPABILITY_UNAVAILABLE -> AI_GATEWAY_FAILURE_MESSAGE;
            case INTERNAL_ERROR -> INTERNAL_ERROR_MESSAGE;
        };
    }

    /**
     * 日志中使用的安全路径标识。
     *
     * <p>不记录原始请求 URI：路径本身可能承载用户输入、资源名称或凭据
     * （例如 {@code /api/assets/<用户提供的名称>}），而日志的留存与传播范围
     * 不受发起请求的调用方控制（ADR-0002）。
     *
     * <p>因此只记录实际匹配到的路由模板。未匹配到业务路由时，记录的可能是框架
     * 兜底 pattern（例如静态资源处理器的 {@code /**}）；连兜底 pattern 都不存在时
     * 使用固定标识。两者都不包含调用方提供的路径内容。
     */
    private static String loggedRoute(HttpServletRequest request) {
        Object matchedPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return (matchedPattern != null) ? matchedPattern.toString() : UNMATCHED_ROUTE;
    }

    private static String loggedRoute(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return loggedRoute(servletWebRequest.getRequest());
        }
        return UNMATCHED_ROUTE;
    }

    /**
     * 写入响应体的请求路径。
     *
     * <p>这里保留原始 URI：它回传给的是发起本次请求的调用方，
     * 该调用方本就持有这个值，不构成向第三方披露。
     * 日志路径的限制与理由见 {@link #loggedRoute(HttpServletRequest)}。
     */
    private static String requestPath(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return UNMATCHED_ROUTE;
    }
}
