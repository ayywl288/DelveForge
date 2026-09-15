package com.ayywl.delveforge.app.api;

import com.ayywl.delveforge.app.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证连通性端点可通过 HTTP 访问，且响应形状符合 Frontend 的预期。
 *
 * <p>使用 Web 切片测试，不启动 DataSource / MyBatis 等 Persistence 组件。
 */
@WebMvcTest(SystemConnectivityController.class)
@Import(ApiExceptionHandler.class)
class SystemConnectivityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsConnectivityPayload() throws Exception {
        mockMvc.perform(get("/api/system/connectivity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("delveforge"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
