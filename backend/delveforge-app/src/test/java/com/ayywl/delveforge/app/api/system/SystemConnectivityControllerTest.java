package com.ayywl.delveforge.app.api.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证连通性端点可通过真实 Spring MVC 链路访问。
 *
 * <p>使用 Web 切片：装配真实的 DispatcherServlet、HandlerMapping 与异常解析器，
 * 但不启动 DataSource / MyBatis 等 Persistence 组件。
 *
 * <p>本类刻意不显式注册 {@code ApiExceptionHandler}：统一错误映射必须能被
 * Spring 自行发现，否则它会静默失效。下面的协议错误用例正是这条链路的验证。
 */
@WebMvcTest(SystemConnectivityController.class)
@ExtendWith(OutputCaptureExtension.class)
class SystemConnectivityControllerTest {

    /** 形态上像凭据的路径片段，用于验证它不会经由日志泄漏。 */
    private static final String PATH_MARKER = "sk-live-path-marker";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsConnectivityPayload() throws Exception {
        mockMvc.perform(get("/api/system/connectivity"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.service").value("delveforge"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * 方法不被支持时，统一错误映射必须生效，且保留框架提供的 Allow 头。
     */
    @Test
    void methodNotAllowedGoesThroughUnifiedErrorMapping() throws Exception {
        mockMvc.perform(post("/api/system/connectivity"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/system/connectivity"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * 未映射路径同样必须返回统一错误形状，而不是框架默认结构。
     */
    @Test
    void unknownPathReturnsUnifiedErrorShape() throws Exception {
        mockMvc.perform(get("/api/system/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/system/does-not-exist"));
    }

    /**
     * 未匹配到业务路由时，原始请求路径不得写入日志。
     *
     * <p>路径可能承载用户输入、资源名称或凭据，而日志的留存与传播范围
     * 不受调用方控制（ADR-0002）。
     *
     * <p>日志记录的是路由模板或框架兜底 pattern（如 {@code /**}），
     * 而不是调用方提供的路径。这里断言的是"原始 URI 没有进入日志"这一性质，
     * 不锁定具体使用哪种标识——那属于实现细节，会随路由配置变化。
     */
    @Test
    void unmatchedRequestPathIsNotWrittenToLogs(CapturedOutput output) throws Exception {
        String requestedPath = "/api/system/" + PATH_MARKER;

        mockMvc.perform(get(requestedPath)).andExpect(status().isNotFound());

        assertFalse(output.getOut().contains(requestedPath),
                "原始请求路径不得写入日志");
        assertTrue(output.getOut().contains("operation=interface.request path="),
                "仍应保留 path 字段用于定位失败请求");
    }
}
