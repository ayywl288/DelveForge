package com.ayywl.delveforge.app.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证统一错误映射、协议错误处理与日志脱敏行为。
 *
 * <p>使用 standalone 方式装配 Controller 与 Advice，不需要完整 Spring 上下文。
 * 这里的 Probe Controller 只存在于测试中，不会被组件扫描，也不进入生产代码。
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    /**
     * 凭据形态的内容，同时埋进异常 message 与原因链。
     * 任何日志级别、任何分支都不得把它写进日志。
     */
    private static final String CREDENTIAL_LIKE = "Bearer sk-live-must-never-be-logged";

    /** 形态上像凭据的路径片段，URL 安全，用于验证路径不会经由日志泄漏。 */
    private static final String PATH_MARKER = "sk-live-path-marker";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void mapsInvalidArgumentToBadRequestWithSafeMessage() throws Exception {
        String body = mockMvc.perform(get("/probe/invalid-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求内容不合法，详细信息见服务端日志"))
                .andExpect(jsonPath("$.path").value("/probe/invalid-argument"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(CREDENTIAL_LIKE), "响应体不得回传任意异常文本");
    }

    @Test
    void mapsAiGatewayFailureToBadGatewayWithoutLeakingProviderDetail() throws Exception {
        mockMvc.perform(get("/probe/ai-gateway"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("外部 AI 能力调用失败，详细信息见服务端日志"));
    }

    @Test
    void mapsWorkspaceFailureToInternalServerError() throws Exception {
        mockMvc.perform(get("/probe/workspace"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("本地能力调用失败，详细信息见服务端日志"));
    }

    /**
     * 默认级别下，异常 message 与原因链中的凭据都不得进入日志。
     */
    @Test
    void credentialLikeTextIsNeverLoggedAtDefaultLevel(CapturedOutput output) throws Exception {
        invokeAllFailureProbes();

        assertFalse(output.getOut().contains(CREDENTIAL_LIKE),
                "异常 message 与原因链中的凭据不得写入默认级别日志");
    }

    /**
     * 本类的日志级别不是例外：即使显式开启 DEBUG，本类也不得把凭据写进日志。
     *
     * <p>这里刻意只提高本类的级别，不提高 root。
     * 提高 root 会让 Spring 自己的 {@code ExceptionHandlerExceptionResolver} 在 DEBUG 下
     * 输出 {@code Resolved [<异常.toString()>]}，那是框架行为、发生在本类之外，
     * 由 {@code application.yml} 中固定的框架包级别控制，
     * 见 {@code DelveForgeApplicationTests#frameworkLoggersThatDumpRawExceptionsArePinned}。
     */
    @Test
    void ourHandlerNeverLogsCredentialLikeTextEvenAtDebug(CapturedOutput output) throws Exception {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger handlerLogger = loggerContext.getLogger(ApiExceptionHandler.class);

        Level previousHandler = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        try {
            invokeAllFailureProbes();

            assertFalse(output.getOut().contains(CREDENTIAL_LIKE),
                    "本类开启 DEBUG 后凭据同样不得写入日志（AGENTS.md §8.8 不区分级别）\n"
                            + offendingLines(output.getOut()));
        } finally {
            handlerLogger.setLevel(previousHandler);
        }
    }

    /**
     * 不记录异常文本，但仍必须保留定位失败所需的信息：异常类型与堆栈位置。
     */
    @Test
    void logsExceptionTypeAndFramesForDiagnosis(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe/unexpected"));

        assertTrue(output.getOut().contains("operation=interface.request"), "应保留 operation 上下文");
        assertTrue(output.getOut().contains("path=/probe/unexpected"), "应保留资源标识");
        assertTrue(output.getOut().contains("java.lang.IllegalStateException"), "应保留异常类型");
        assertTrue(output.getOut().contains("at com.ayywl.delveforge.app.error.ApiExceptionHandlerTest"),
                "应保留堆栈位置，否则无法定位失败点");
    }

    /**
     * 已匹配路由时，日志记录路由模板而不是原始 URI。
     *
     * <p>路径变量可能承载用户输入、资源名称或凭据，
     * 而日志的留存与传播范围不受调用方控制（ADR-0002）。
     */
    @Test
    void matchedRouteLogsTemplateInsteadOfRawUri(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe/asset/" + PATH_MARKER))
                .andExpect(status().isBadRequest());

        assertFalse(output.getOut().contains(PATH_MARKER),
                "路径变量中的内容不得写入日志");
        assertTrue(output.getOut().contains("path=/probe/asset/{name}"),
                "日志应记录路由模板而不是原始请求 URI");
    }

    /**
     * Spring MVC 自身的协议层异常已有明确状态码语义，不能被兜底分支压成 500。
     */
    @Test
    void preservesProtocolLevelStatusInsteadOfCollapsingToInternalError() throws Exception {
        mockMvc.perform(get("/probe/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/probe/post-only"));
    }

    /**
     * 405 必须保留框架提供的 Allow 头，否则调用方无法知道该用哪个方法。
     */
    @Test
    void methodNotAllowedPreservesAllowHeader() throws Exception {
        mockMvc.perform(get("/probe/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
    }

    /**
     * 请求体无法解析属于请求本身的问题，应为 400 而不是 500。
     */
    @Test
    void malformedJsonIsMappedToBadRequest() throws Exception {
        mockMvc.perform(post("/probe/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求内容不合法，详细信息见服务端日志"));
    }

    private static String offendingLines(String output) {
        StringBuilder offending = new StringBuilder("包含凭据的日志行:\n");
        for (String line : output.split("\\R")) {
            if (line.contains(CREDENTIAL_LIKE)) {
                offending.append("  ").append(line, 0, Math.min(line.length(), 400)).append('\n');
            }
        }
        return offending.toString();
    }

    private void invokeAllFailureProbes() throws Exception {
        mockMvc.perform(get("/probe/invalid-argument"));
        mockMvc.perform(get("/probe/ai-gateway"));
        mockMvc.perform(get("/probe/workspace"));
        mockMvc.perform(get("/probe/unexpected"));
        mockMvc.perform(get("/probe/post-only"));
        mockMvc.perform(post("/probe/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":"));
    }

    /**
     * 只存在于测试中的探针 Controller。
     *
     * <p>刻意声明为非 static 内部类：Spring 的组件扫描跳过非独立（non-independent）
     * 类，因此它不会被 {@code DelveForgeApplicationTests} 的上下文扫描到，
     * 同时仍可被 standaloneSetup 装配。
     *
     * <p>每个失败路径都在 message 与原因链中埋入凭据形态的内容，
     * 用于验证日志与响应体都不会泄漏。
     */
    @RestController
    @RequestMapping("/probe")
    class ErrorProbeController {

        @GetMapping("/invalid-argument")
        void invalidArgument() {
            throw new IllegalArgumentException("内部校验细节: " + CREDENTIAL_LIKE);
        }

        @GetMapping("/ai-gateway")
        void aiGateway() {
            throw new AiGatewayException("调用失败: " + CREDENTIAL_LIKE,
                    new RuntimeException("Authorization: " + CREDENTIAL_LIKE));
        }

        @GetMapping("/workspace")
        void workspace() {
            throw new WorkspaceException("git clone 失败: " + CREDENTIAL_LIKE,
                    new RuntimeException("remote: " + CREDENTIAL_LIKE));
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("内部细节: " + CREDENTIAL_LIKE);
        }

        @GetMapping("/asset/{name}")
        void assetByName(@PathVariable String name) {
            throw new IllegalArgumentException("按名称查询失败: " + name);
        }

        @PostMapping("/post-only")
        void postOnly() {
        }

        @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        void json(@RequestBody JsonProbe body) {
        }
    }

    record JsonProbe(String name) {
    }
}
