package com.ayywl.delveforge.app.error;

import com.ayywl.delveforge.application.evolution.execution.EvolutionExecutionException;
import com.ayywl.delveforge.application.evolution.planning.EvolutionPlanNotFoundException;
import com.ayywl.delveforge.application.evolution.planning.PlanningExtractionException;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationBudgetExceededException;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationException;
import com.ayywl.delveforge.application.evolution.preparation.UnsupportedVerificationCriteriaException;
import com.ayywl.delveforge.application.evolution.provisioning.WorkingCopyNotFoundException;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.StaleUserProfileRevisionException;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.UserProfileNotConfirmedException;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.persistence.EvolutionLifecycleConflictException;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanAlreadyExistsException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionIntegrityConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionStatusConflictException;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetAlreadyRegisteredException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyBasisChangedException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyTextNotExactException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceStorageUnavailableException;
import com.ayywl.delveforge.application.project.EvolutionFlowScopeException;
import com.ayywl.delveforge.application.project.ProjectContextConflictException;
import com.ayywl.delveforge.application.project.ProjectDeletionBlockedException;
import com.ayywl.delveforge.application.project.ProjectNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryNotAnalyzableException;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.domain.asset.AssetAuthorizationConflictException;
import com.ayywl.delveforge.domain.asset.AssetEvolutionNotAllowedException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetNotReadableException;
import com.ayywl.delveforge.domain.direction.ProductDirectionDiscoveryException;
import com.ayywl.delveforge.domain.direction.ProductDirectionStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningPreconditionException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningRejectedException;
import com.ayywl.delveforge.domain.evolution.EvolutionStepNotFoundException;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

import jakarta.servlet.http.HttpServletRequest;
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

import java.time.Instant;

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

    @ExceptionHandler(ProjectNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleProjectNotFound(ProjectNotFoundException exception, HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=NOT_FOUND exception={}",
                loggedRoute(request), describe(exception));
        return new ApiErrorResponse(ApiErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE, request.getRequestURI(), Instant.now());
    }

    @ExceptionHandler(ProjectContextConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleProjectConflict(ProjectContextConflictException exception, HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=PROJECT_CONTEXT_CONFLICT exception={}",
                loggedRoute(request), describe(exception));
        return new ApiErrorResponse(ApiErrorCode.PROJECT_CONTEXT_CONFLICT,
                messageFor(ApiErrorCode.PROJECT_CONTEXT_CONFLICT), request.getRequestURI(), Instant.now());
    }

    @ExceptionHandler(ProjectDeletionBlockedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleProjectDeletionBlocked(ProjectDeletionBlockedException exception,
            HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=PROJECT_DELETION_BLOCKED exception={}",
                loggedRoute(request), describe(exception));
        return new ApiErrorResponse(ApiErrorCode.PROJECT_DELETION_BLOCKED,
                messageFor(ApiErrorCode.PROJECT_DELETION_BLOCKED), request.getRequestURI(), Instant.now());
    }

    /** 原因链深度上限，避免异常链被异常构造时日志失控。 */
    private static final int MAX_CAUSE_DEPTH = 10;

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static final String INVALID_REQUEST_MESSAGE = "请求内容不合法，详细信息见服务端日志";
    private static final String NOT_FOUND_MESSAGE = "指定的资源不存在";
    private static final String CONFLICT_MESSAGE = "请求与当前状态冲突，详细信息见服务端日志";
    private static final String AI_GATEWAY_FAILURE_MESSAGE = "外部 AI 能力调用失败，详细信息见服务端日志";
    private static final String DIRECTION_DISCOVERY_REJECTED_MESSAGE =
            "AI 返回的候选方向不满足领域要求，详细信息见服务端日志";
    private static final String WORKSPACE_FAILURE_MESSAGE = "本地能力调用失败，详细信息见服务端日志";
    private static final String WORKSPACE_STORAGE_FAILURE_MESSAGE =
            "WorkingCopy 存储不可用。请检查配置目录、权限、链接及已有目标；系统没有切换存储位置。";
    private static final String INTERNAL_ERROR_MESSAGE = "服务内部错误，详细信息见服务端日志";

    /** 未匹配到任何路由时，日志中使用的固定路径标识。 */
    private static final String UNMATCHED_ROUTE = "<unmatched>";

    /** 托管存储配置不可用，固定错误文案不包含路径或异常正文。 */
    @ExceptionHandler(WorkspaceStorageUnavailableException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleWorkspaceStorageFailure(WorkspaceStorageUnavailableException exception,
            HttpServletRequest request) {
        log.error("operation=interface.request path={} result=WORKSPACE_STORAGE_UNAVAILABLE exception={}",
                loggedRoute(request), describe(exception));
        return new ApiErrorResponse(ApiErrorCode.WORKSPACE_STORAGE_UNAVAILABLE,
                WORKSPACE_STORAGE_FAILURE_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /** 请求字段不合法；JDK 或第三方异常正文不作为调用方可见文案。 */
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
     * 领域对象当前状态不允许该操作。
     *
     * <p>请求本身可以理解，只是对象当前处于不能执行该操作的状态（例如对已经进入
     * REVIEWING 的 Profile 再次执行状态转移、修改 CONFIRMED 的 Profile）。
     * 这属于可预期的冲突，不是服务端故障，因此与
     * {@link IllegalArgumentException}（请求不合法）和兜底处理（未预期错误）区分开。
     *
     * <p>只映射领域自己的状态异常类型，不映射通用的 {@link IllegalStateException}：
     * 后者可能来自 JDK 或第三方库，把它们一律当成 409 会掩盖真正的服务端错误。
     */
    @ExceptionHandler(UserProfileStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleStateConflict(UserProfileStateException exception,
                                                HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=CONFLICT exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.CONFLICT, CONFLICT_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * 请求指向的软件资产或分析快照不存在。
     *
     * <p>与 {@link IllegalArgumentException} 同样的区分方式：请求形态合法，只是目标不存在。
     */
    @ExceptionHandler({SoftwareAssetNotFoundException.class,
            RepositoryProfileNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleRepositoryAnalysisNotFound(RuntimeException exception,
                                                             HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=NOT_FOUND exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * 资产当前状态不允许被分析。
     *
     * <pre>
     * SoftwareAssetNotReadableException   资产自身声明的读取权限不允许读取（INV-A01）
     * RepositoryNotAnalyzableException    位置不是可读的 Repository，
     *                                     或该 Repository 没有可分析的材料
     * </pre>
     *
     * <p>两者都不是「请求不合法」，也不是服务端故障：资产确实存在，请求也可以理解，
     * 只是它当前不能被分析。映射为 409 而不是 400，调用方据此知道要改变的是资产状态
     * （或换一个位置），而不是请求写法。
     *
     * <p>只映射这两个项目自有类型：不用 {@link IllegalArgumentException} 兜，
     * 也不用 {@code WorkspaceException}（后者表示本地能力调用失败，属于服务端一侧）。
     */
    @ExceptionHandler({SoftwareAssetNotReadableException.class,
            RepositoryNotAnalyzableException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleAssetNotAnalyzable(RuntimeException exception,
                                                     HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=CONFLICT exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.CONFLICT, CONFLICT_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * 请求指向的 Product Direction 或 Evolution Plan 不存在。
     *
     * <p>与其它 NOT_FOUND 相同的区分方式：请求形态合法，只是目标不存在。
     */
    @ExceptionHandler({ProductDirectionNotFoundException.class, EvolutionPlanNotFoundException.class,
            EvolutionStepNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleProductDirectionNotFound(
            RuntimeException exception, HttpServletRequest request) {

        log.warn("operation=interface.request path={} result=NOT_FOUND exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * 本次 Product Direction Discovery 的前置条件不满足。
     *
     * <pre>
     * StaleUserProfileRevisionException   调用方依据的那一版已经不是当前版本
     * UserProfileNotConfirmedException    用于发现的 Profile 尚未 CONFIRMED
     * EvolutionPlanningPreconditionException 规划基线不满足要求
     * 资产许可拒绝由 handleAssetAuthorizationConflict 按具体原因单独映射。
     * EvolutionPlanAlreadyExistsException  不得覆盖已保存的规划
     * </pre>
     *
     * <p>两者都不是「请求写错了」：请求可以理解，只是它依据的输入当前不成立。
     * 调用方据此知道要重新读取 Profile（必要时重新确认）之后再发起，而不是换一种写法重试。
     * 这正是 409 而不是 400 的语义，也是它与 {@link IllegalArgumentException} 分开的理由。
     */
    @ExceptionHandler({StaleUserProfileRevisionException.class,
            UserProfileNotConfirmedException.class, EvolutionPlanningPreconditionException.class,
            EvolutionPlanAlreadyExistsException.class,
            EvolutionPlanStateException.class, EvolutionLifecycleConflictException.class, WorkingCopyBasisChangedException.class,
            EvolutionExecutionException.class,
            ExecutionPreparationBudgetExceededException.class, WorkingCopyTextNotExactException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleDiscoveryPreconditionConflict(
            RuntimeException exception, HttpServletRequest request) {

        log.warn("operation=interface.request path={} result=CONFLICT exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.CONFLICT, CONFLICT_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    @ExceptionHandler(WorkingCopyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleWorkingCopyNotFound(WorkingCopyNotFoundException exception, HttpServletRequest request) {
        log.warn("operation=interface.request path={} result=NOT_FOUND exception={}", loggedRoute(request), describe(exception));
        return new ApiErrorResponse(ApiErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE, request.getRequestURI(), Instant.now());
    }

    /**
     * 用户对 Product Direction 的生命周期操作与它当前的状态冲突。
     *
     * <pre>
     * ProductDirectionStateException              该方向当前状态不允许这次操作
     *                                             （例如对已经 SELECTED 的方向再次 select）
     * ProductDirectionSelectionConflictException  写入时已经存在另一个当前 SELECTED 方向
     * ProductDirectionStatusConflictException     本次依据的状态已经变化，或写入与并发写冲突
     * ProductDirectionIntegrityConflictException  存储状态违反领域不变量（多于一个当前方向）
     * </pre>
     *
     * <p>这些都不是「请求写错了」：请求可以理解，只是与当前状态冲突。调用方据此知道要重新
     * 读取方向、换一个目标，或（最后一种）去修复数据，而不是换一种写法重试——这正是 409
     * 而不是 400 的语义。
     *
     * <p>只映射项目自有的类型。特别是**不**把持久化层的异常兜进来：那些已经由 Adapter 在
     * Port 边界翻译成上面的类型（AGENTS.md §8.7），直接映射 Spring 的数据访问异常会让本层
     * 无从区分「可判定的冲突」与「真的写坏了」。
     */
    @ExceptionHandler({ProductDirectionStateException.class,
            ProductDirectionSelectionConflictException.class,
            ProductDirectionStatusConflictException.class,
            ProductDirectionIntegrityConflictException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleProductDirectionLifecycleConflict(
            RuntimeException exception, HttpServletRequest request) {

        log.warn("operation=interface.request path={} result=CONFLICT exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.CONFLICT, CONFLICT_MESSAGE,
                request.getRequestURI(), Instant.now());
    }

    /**
     * 一次 Product Direction Discovery 的结果不满足领域要求。
     *
     * <p>调用方的请求本身没有问题，失败出在模型给出的候选方向上——数量、依据的出处、
     * 三类关键判断的可追溯性，或候选资产与它实际依据的分析对不上。模型调用本身是成功的，
     * 因此它与 {@link IllegalArgumentException}（请求不合法）和兜底处理（未预期的服务端
     * 错误）都不同。
     *
     * <p>归入外部能力一侧而不是服务端故障：DelveForge 不产生这个结果，是它依赖的模型
     * 能力产出了系统无法接受的东西。因此映射为 502，与
     * {@link AiGatewayException}「模型返回内容不满足约定」同一类，只是发生在更后一步——
     * 那一步是结构是否符合约定，这一步是结果是否满足领域规则。
     */
    @ExceptionHandler(ProductDirectionDiscoveryException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiErrorResponse handleDirectionDiscoveryRejected(
            ProductDirectionDiscoveryException exception, HttpServletRequest request) {

        log.error("operation=interface.request path={} capability=ai-gateway result=REJECTED exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.EXTERNAL_CAPABILITY_UNAVAILABLE,
                DIRECTION_DISCOVERY_REJECTED_MESSAGE,
                request.getRequestURI(),
                Instant.now());
    }

    @ExceptionHandler(UnsupportedVerificationCriteriaException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiErrorResponse handleUnsupportedExecutionCriteria(
            UnsupportedVerificationCriteriaException exception, HttpServletRequest request) {
        ApiErrorCode code = ApiErrorCode.EXECUTION_CRITERIA_UNSUPPORTED;
        log.warn("operation=evolution.execution path={} phase=PREFLIGHT code={} criterionIndexes={} exception={}",
                loggedRoute(request), code, exception.criterionIndexes(), describe(exception));
        return new ApiErrorResponse(code, messageFor(code), request.getRequestURI(), Instant.now());
    }

    @ExceptionHandler(ExecutionPreparationException.class)
    public ResponseEntity<ApiErrorResponse> handleExecutionPreparationFailure(
            ExecutionPreparationException exception, HttpServletRequest request) {
        boolean providerFailure = exception.phase() == ExecutionPreparationException.Phase.PROVIDER_CALL;
        ApiErrorCode code = providerFailure ? ApiErrorCode.EXECUTION_AI_FAILED : ApiErrorCode.CODING_PROPOSAL_INVALID;
        HttpStatus status = providerFailure ? HttpStatus.BAD_GATEWAY : HttpStatus.UNPROCESSABLE_ENTITY;
        log.error("operation=evolution.execution path={} phase={} code={} exception={}",
                loggedRoute(request), exception.phase(), code, describe(exception));
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(code, messageFor(code), request.getRequestURI(), Instant.now()));
    }

    /** 其它业务的 AI Gateway 失败；规划与执行准备的阶段由独立分支分类。 */
    @ExceptionHandler(AiGatewayException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiErrorResponse handleAiGatewayFailure(RuntimeException exception,
                                                   HttpServletRequest request) {
        log.error("operation=interface.request path={} capability=ai-gateway result=FAILED exception={}",
                loggedRoute(request), describe(exception));

        return new ApiErrorResponse(
                ApiErrorCode.EXTERNAL_CAPABILITY_UNAVAILABLE,
                AI_GATEWAY_FAILURE_MESSAGE,
                request.getRequestURI(),
                Instant.now());
    }

    @ExceptionHandler(PlanningExtractionException.class)
    public ResponseEntity<ApiErrorResponse> handlePlanningExtractionFailure(
            PlanningExtractionException exception, HttpServletRequest request) {
        ApiErrorCode code = switch (exception.phase()) {
            case INPUT_SERIALIZATION -> ApiErrorCode.INTERNAL_ERROR;
            case PROVIDER_CALL -> ApiErrorCode.PLANNING_AI_FAILED;
            case PROPOSAL_PARSE -> ApiErrorCode.PLANNING_PROPOSAL_INVALID;
            case EVIDENCE_RESOLVE -> ApiErrorCode.PLANNING_EVIDENCE_INVALID;
        };
        log.error("operation=evolution.planning path={} phase={} code={} exception={}",
                loggedRoute(request), exception.phase(), code, describe(exception));
        HttpStatus status = exception.phase() == PlanningExtractionException.Phase.INPUT_SERIALIZATION
                ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(code, messageFor(code), request.getRequestURI(), Instant.now()));
    }

    @ExceptionHandler(EvolutionPlanningRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiErrorResponse handlePlanningRejected(
            EvolutionPlanningRejectedException exception, HttpServletRequest request) {
        ApiErrorCode code = switch (exception.reason()) {
            case CURRENT_CAPABILITIES_NOT_FROM_BASE_PROFILE, CURRENT_MODULES_NOT_FROM_BASE_PROFILE,
                    CURRENT_LIMITATIONS_NOT_FROM_BASE_PROFILE -> ApiErrorCode.PLANNING_CURRENT_STATE_INVALID;
            default -> ApiErrorCode.PLANNING_REJECTED;
        };
        log.error("operation=evolution.planning path={} phase=DOMAIN_ACCEPTANCE code={} reason={} exception={}",
                loggedRoute(request), code, exception.reason(), describe(exception));
        return new ApiErrorResponse(code, messageFor(code), request.getRequestURI(), Instant.now());
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
            case WORKSPACE_STORAGE_UNAVAILABLE -> WORKSPACE_STORAGE_FAILURE_MESSAGE;
            case NOT_FOUND -> NOT_FOUND_MESSAGE;
            case CONFLICT -> CONFLICT_MESSAGE;
            case EXTERNAL_CAPABILITY_UNAVAILABLE -> AI_GATEWAY_FAILURE_MESSAGE;
            case EXECUTION_CRITERIA_UNSUPPORTED ->
                    "当前 Step 包含执行器不支持的验收标准，无法自动验证；重复执行不会解决，请审阅计划的验证标准";
            case EXECUTION_AI_FAILED ->
                    "执行准备的 AI 调用或 Provider 响应处理失败，尚未开始代码变更；请查询当前状态，不会自动重试";
            case CODING_PROPOSAL_INVALID ->
                    "Provider 已返回内容，但代码提案未通过解析或契约校验，尚未开始代码变更；请查询当前状态，重复执行不保证解决";
            case PLANNING_AI_FAILED -> "规划的 AI 调用或 Provider 响应处理失败，本次未保存计划；可核对后明确重新请求，可能再次计费";
            case PLANNING_PROPOSAL_INVALID -> "AI 规划内容未满足结构契约，本次未保存计划；可明确重新请求，可能再次计费";
            case PLANNING_EVIDENCE_INVALID -> "AI 规划引用了输入中不存在的依据，本次未保存计划；可明确重新请求，可能再次计费";
            case PLANNING_CURRENT_STATE_INVALID -> "AI 规划的现状条目不属于所选仓库画像原文，已拒绝且未保存计划；可明确重新请求，可能再次计费";
            case PLANNING_REJECTED -> "AI 规划未通过产品意图、复用能力或依据等领域校验，已拒绝且未保存计划；可明确重新请求，可能再次计费";
            case INTERNAL_ERROR -> INTERNAL_ERROR_MESSAGE;
            case ASSET_TYPE_UNSUPPORTED -> "当前资产类型不能作为演化基础";
            case ASSET_READ_PERMISSION_REQUIRED -> "请在已有仓库的授权信息中明确提交读取许可";
            case ASSET_USAGE_AUTHORIZATION_REQUIRED -> "请核对使用条款，并在已有仓库中明确提交使用授权";
            case ASSET_LICENSE_INFORMATION_REQUIRED -> "分析允许许可证未知；演化规划前请补齐已有仓库的许可证及适用范围";
            case ASSET_AUTHORIZATION_CONFLICT -> "仓库授权版本已变化，请重新读取并核对；本次操作未提交";
            case ASSET_ALREADY_REGISTERED -> "此仓库已登记，请选择已有资产；补充许可证应修改原记录";
            case PROJECT_CONTEXT_CONFLICT -> "项目关联或版本已变化，请重新打开项目核对；本次上下文未保存";
            case PROJECT_DELETION_BLOCKED -> "存在已授权或未决执行、未验证版本或缺失的执行记录，不能删除项目；请先查询真实状态";
            case EVOLUTION_FLOW_SCOPE_CONFLICT -> "流程归属缺失或不一致，请只读查看历史";
        };
    }

    @ExceptionHandler({AssetEvolutionNotAllowedException.class, AssetAuthorizationConflictException.class,
            SoftwareAssetAlreadyRegisteredException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleAssetAuthorizationConflict(RuntimeException exception,
            HttpServletRequest request) {
        ApiErrorCode code;
        if (exception instanceof AssetEvolutionNotAllowedException denied) {
            code = switch (denied.reason()) {
                case ASSET_TYPE_UNSUPPORTED -> ApiErrorCode.ASSET_TYPE_UNSUPPORTED;
                case READ_PERMISSION_REQUIRED -> ApiErrorCode.ASSET_READ_PERMISSION_REQUIRED;
                case USAGE_AUTHORIZATION_REQUIRED -> ApiErrorCode.ASSET_USAGE_AUTHORIZATION_REQUIRED;
                case LICENSE_INFORMATION_REQUIRED -> ApiErrorCode.ASSET_LICENSE_INFORMATION_REQUIRED;
            };
        } else if (exception instanceof AssetAuthorizationConflictException) {
            code = ApiErrorCode.ASSET_AUTHORIZATION_CONFLICT;
        } else {
            code = ApiErrorCode.ASSET_ALREADY_REGISTERED;
        }
        log.warn("operation=interface.request path={} result={} exception={}",
                loggedRoute(request), code, describe(exception));
        return new ApiErrorResponse(code, messageFor(code), request.getRequestURI(), Instant.now());
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
    @ExceptionHandler(EvolutionFlowScopeException.class)
    public ResponseEntity<ApiErrorResponse> flowScopeConflict(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(ApiErrorCode.EVOLUTION_FLOW_SCOPE_CONFLICT,
                        "流程归属缺失或不一致，请只读查看历史。", request.getRequestURI(), Instant.now()));
    }
}
