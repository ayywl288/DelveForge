package com.ayywl.delveforge.workbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** 预览在独立的浏览器来源中运行，只有工作台自己的页面可以发起控制操作。 */
@Component
final class LocalWorkbenchRequests extends OncePerRequestFilter {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private final int uiPort;

    LocalWorkbenchRequests(@Value("${delveforge.workbench.ui-port:5173}") int uiPort) {
        this.uiPort = uiPort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/workbench/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String origin = request.getHeader("Origin");
        if (!LOOPBACK.contains(request.getServerName())
                || origin != null && !trusted(origin, request.getServerPort())
                || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean trusted(String origin, int backendPort) {
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme())
                    && uri.getHost() != null
                    && LOOPBACK.contains(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getRawPath().isEmpty()
                    && (uri.getPort() == uiPort || uri.getPort() == backendPort);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
