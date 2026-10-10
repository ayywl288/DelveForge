package com.ayywl.delveforge.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepSeekStreamingResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"EOF", "STOP_ONLY", "DONE_ONLY", "LENGTH", "ABORTED"})
    void neverAcceptsACompleteJsonFragmentWithoutSuccessfulTermination(String ending) {
        String content = chunk("{}", null);
        String body = switch (ending) {
            case "STOP_ONLY" -> content + chunk("", "stop");
            case "DONE_ONLY" -> content + "data: [DONE]\n\n";
            case "LENGTH" -> content + chunk("", "length") + "data: [DONE]\n\n";
            case "ABORTED" -> content + chunk("", "aborted") + "data: [DONE]\n\n";
            default -> content;
        };
        var failure = assertThrows(DeepSeekStreamingResponse.Rejected.class, () -> read(body, 65536));
        assertEquals("PROVIDER_RESPONSE_INCOMPLETE", failure.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "4294967296"})
    void rejectsAnotherChoiceIncludingAnIndexThatWouldOverflowInt(String index) {
        String body = chunk("{}", "stop").replace("\"index\":0", "\"index\":" + index)
                + "data: [DONE]\n\n";
        var failure = assertThrows(DeepSeekStreamingResponse.Rejected.class, () -> read(body, 65536));
        assertEquals("PROVIDER_RESPONSE_INVALID", failure.code());
    }

    @Test
    void acceptsMultilineEventsAndUsageAfterStopWithoutReturningMetadata() throws Exception {
        String body = ": keep-alive\r\n\r\nid: private-id\r\nevent: message\r\n"
                + "data: {\"object\":\"chat.completion.chunk\",\r\n"
                + "data: \"choices\":[{\"index\":0,\"delta\":{\"content\":\"{}\"},"
                + "\"finish_reason\":\"stop\"}]}\r\n\r\n"
                + "data: {\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{"
                + "\"prompt_tokens\":5,\"completion_tokens\":7,\"completion_tokens_details\":{"
                + "\"reasoning_tokens\":4}}}\n\n" + "data: [DONE]\n\n";
        var stats = new DeepSeekStreamingResponse.Stats();
        assertEquals("{}", DeepSeekStreamingResponse.read(new StringReader(body), mapper, 65536, stats, () -> 10));
        assertEquals(5, stats.promptTokens);
        assertEquals(7, stats.completionTokens);
        assertEquals(4, stats.reasoningTokens);
        assertEquals(2, stats.contentBytes);
    }

    @Test
    void enforcesEventBudgetInUtf8Bytes() {
        String body = chunk("中".repeat(20), "stop") + "data: [DONE]\n\n";
        int smallerThanUtf8Bytes = body.indexOf('\n');
        var failure = assertThrows(DeepSeekStreamingResponse.Rejected.class,
                () -> read(body, smallerThanUtf8Bytes));
        assertEquals("PROVIDER_EVENT_LIMIT_EXCEEDED", failure.code());
    }

    @Test
    void rejectsTrailingJsonAndDataAfterStop() {
        for (String body : new String[]{chunk("{}", "stop") + chunk("extra", null) + "data: [DONE]\n\n",
                chunk("{}", "stop").replace("}\n\n", "} {}\n\n") + "data: [DONE]\n\n"}) {
            var failure = assertThrows(DeepSeekStreamingResponse.Rejected.class, () -> read(body, 65536));
            assertEquals("PROVIDER_RESPONSE_INVALID", failure.code());
        }
    }

    private String read(String body, int eventBytes) throws Exception {
        return DeepSeekStreamingResponse.read(new StringReader(body), mapper, eventBytes,
                new DeepSeekStreamingResponse.Stats(), () -> 10);
    }

    private static String chunk(String content, String finish) {
        return "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"content\":\"" + content + "\"},\"finish_reason\":"
                + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }
}
