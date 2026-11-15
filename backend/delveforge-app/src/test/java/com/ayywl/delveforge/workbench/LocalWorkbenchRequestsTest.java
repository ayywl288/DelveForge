package com.ayywl.delveforge.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalWorkbenchRequestsTest {
    private int request(String host, String origin) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/workbench/runs/example/stop");
        request.setServerName(host);
        request.setServerPort(8080);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        var response = new MockHttpServletResponse();
        new LocalWorkbenchRequests(5173).doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void controlAcceptsLocalClientsAndWorkbenchButRejectsPreviewOrigin() throws Exception {
        assertEquals(200, request("127.0.0.1", null));
        assertEquals(200, request("127.0.0.1", "http://localhost:5173"));
        assertEquals(200, request("localhost", "http://localhost:8080"));
        assertEquals(
                403, request("localhost", "http://p-00112233445566778899aabb.localhost:12345"));
        assertEquals(403, request("localhost", "null"));
        assertEquals(403, request("other.example", null));
    }
}
