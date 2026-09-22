package com.ayywl.delveforge.app.api.system;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统连通性端点。
 *
 * <p>只用于验证 Frontend 与 Local Backend 之间的 HTTP 链路可用，
 * 不承载任何业务语义，也不反映业务状态。产品能力接入后本端点仍保持技术性质。
 *
 * <p>Controller 保持轻量：不承载领域规则、流程编排或本地能力调用（RULE-ARCH-005）。
 * 错误响应由 {@code com.ayywl.delveforge.app.error} 统一处理，这里不构造错误。
 */
@RestController
@RequestMapping("/api/system")
public class SystemConnectivityController {

    private static final String SERVICE_NAME = "delveforge";
    private static final String STATUS_UP = "UP";

    @GetMapping("/connectivity")
    public ConnectivityResponse connectivity() {
        // 仅返回“服务可应答”这一事实，不探测数据库、Provider 等下游依赖，
        // 避免把连通性检查变成对下游可用性的隐式承诺。
        return new ConnectivityResponse(SERVICE_NAME, STATUS_UP, Instant.now());
    }

    /**
     * @param service   服务标识
     * @param status    固定为 UP，仅表示本端点可应答
     * @param timestamp 应答时间
     */
    public record ConnectivityResponse(String service, String status, Instant timestamp) {
    }
}
