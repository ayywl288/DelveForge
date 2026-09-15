package com.ayywl.delveforge.app.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
class SystemConnectivityControllerTest {

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
}
