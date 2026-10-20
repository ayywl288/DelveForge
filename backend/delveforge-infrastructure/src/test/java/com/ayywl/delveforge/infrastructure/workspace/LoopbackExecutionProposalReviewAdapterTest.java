package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationBasis;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionProposalReviewException;
import com.ayywl.delveforge.application.evolution.preparation.ValidatedExecutionProposal;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoopbackExecutionProposalReviewAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> mode = new AtomicReference<>("approve");
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private LoopbackExecutionProposalReviewAdapter adapter() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/review", exchange -> {
            calls.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            received.set(request);
            String response = switch (mode.get()) {
                case "reject" -> "{\"approved\":false}";
                case "mismatch" -> "{\"approved\":true,\"patchSha256\":\"wrong\"}";
                case "string" -> "{\"approved\":\"true\"}";
                case "trailing" -> "{\"approved\":true} {}";
                case "large" -> "x".repeat(5000);
                default -> "{\"approved\":true,\"patchSha256\":\"" + request.path("patchSha256").asText() + "\"}";
            };
            int status = mode.get().equals("redirect") ? 307 : mode.get().equals("error") ? 503 : 200;
            exchange.getResponseHeaders().add("Location", "http://example.invalid/must-not-be-followed");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new LoopbackExecutionProposalReviewAdapter(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/review"), mapper);
    }

    private ValidatedExecutionProposal proposal() {
        ValidatedExecutionProposal validated = mock(ValidatedExecutionProposal.class);
        when(validated.basis()).thenReturn(new ExecutionPreparationBasis(new EvolutionPlanId("plan"),
                new EvolutionStepId("step"), new WorkingCopyId("copy"), "a".repeat(40)));
        when(validated.proposal()).thenReturn(new CodingProposal("a".repeat(40), "change", List.of("src/A.java"),
                "--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n-old\r\n+new\n", List.of(
                        new CodingProposal.VerificationAction(CodingProposal.Action.DIFF_CHECK, "", List.of(1)))));
        return validated;
    }

    @Test
    void actualHttpApprovalBindsExactUnmodifiedPatchAndExecutionBasis() throws Exception {
        var adapter = adapter();
        ValidatedExecutionProposal proposal = proposal();
        adapter.review(proposal);
        assertEquals(1, calls.get());
        assertEquals("plan", received.get().path("planId").asText());
        assertEquals("step", received.get().path("stepId").asText());
        assertEquals("copy", received.get().path("workingCopyId").asText());
        assertEquals("a".repeat(40), received.get().path("expectedRevision").asText());
        assertEquals(proposal.proposal().unifiedDiff(), received.get().path("proposal").path("unifiedDiff").asText());
        assertTrue(received.get().path("patchSha256").asText().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectionIsSpecificAndDoesNotRetry() throws Exception {
        var adapter = adapter();
        mode.set("reject");
        var failure = assertThrows(ExecutionProposalReviewException.class, () -> adapter.review(proposal()));
        assertEquals(ExecutionProposalReviewException.Reason.REJECTED, failure.reason());
        assertEquals(1, calls.get());
    }

    @Test
    void corruptResponsesStatusAndRedirectFailClosedWithoutRetry() throws Exception {
        var adapter = adapter();
        for (String value : List.of("mismatch", "string", "trailing", "large", "redirect", "error")) {
            mode.set(value);
            int before = calls.get();
            var failure = assertThrows(ExecutionProposalReviewException.class, () -> adapter.review(proposal()));
            assertEquals(ExecutionProposalReviewException.Reason.UNAVAILABLE, failure.reason());
            assertEquals(before + 1, calls.get());
        }
        assertNotNull(received.get());
    }

    @Test
    void endpointCannotExpandToRemoteHostCredentialsOrUnboundedProtocols() {
        for (String value : List.of("https://127.0.0.1:1234/review", "http://localhost:1234/review",
                "http://example.invalid:1234/review", "http://127.0.0.1/review",
                "http://user@127.0.0.1:1234/review", "http://127.0.0.1:1234/review?x=y",
                "http://127.0.0.1:1234/review#fragment")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LoopbackExecutionProposalReviewAdapter(URI.create(value), mapper));
        }
    }
}
