package com.ayywl.delveforge.application.port.ai;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 AI Gateway 边界可以在不依赖任何 Provider、也不依赖 Infrastructure 的情况下被实现与使用。
 */
class AiGatewayTest {

    /** 测试用假 Adapter：仅实现 Application 拥有的 Port。 */
    private static final class RecordingAiGateway implements AiGateway {

        private final String response;
        private final List<AiRequest> received = new ArrayList<>();

        private RecordingAiGateway(String response) {
            this.response = response;
        }

        @Override
        public String generate(AiRequest request) {
            received.add(request);
            return response;
        }
    }

    @Test
    void generatesContentWithoutDependingOnAnyProvider() {
        RecordingAiGateway gateway = new RecordingAiGateway("{\"problem\":\"...\"}");

        String content = gateway.generate(
                AiRequest.of(AiRole.USER, "分析这个仓库", AiResponseFormat.JSON));

        assertEquals("{\"problem\":\"...\"}", content);
        assertEquals(1, gateway.received.size());
        assertEquals(AiResponseFormat.JSON, gateway.received.getFirst().responseFormat());
    }

    @Test
    void supportsMultiTurnConversationForUserDiscovery() {
        AiRequest request = new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, "你是一个引导式访谈助手"),
                        new AiMessage(AiRole.USER, "我喜欢做开发者工具"),
                        new AiMessage(AiRole.ASSISTANT, "你更偏好命令行还是图形界面？")),
                AiResponseFormat.TEXT);

        assertEquals(3, request.messages().size());
        assertEquals(AiRole.ASSISTANT, request.messages().get(2).role());
    }

    @Test
    void rejectsRequestWithoutMessages() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiRequest(List.of(), AiResponseFormat.TEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new AiRequest(null, AiResponseFormat.TEXT));
    }

    @Test
    void rejectsRequestWithoutResponseFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiRequest(List.of(new AiMessage(AiRole.USER, "hi")), null));
    }

    @Test
    void rejectsMessageWithoutRoleOrContent() {
        assertThrows(IllegalArgumentException.class, () -> new AiMessage(null, "hi"));
        assertThrows(IllegalArgumentException.class, () -> new AiMessage(AiRole.USER, null));
        assertThrows(IllegalArgumentException.class, () -> new AiMessage(AiRole.USER, "   "));
    }

    @Test
    void requestMessagesCannotBeMutatedFromOutside() {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage(AiRole.USER, "hi"));
        AiRequest request = new AiRequest(messages, AiResponseFormat.TEXT);

        messages.add(new AiMessage(AiRole.USER, "调用方后续追加"));

        assertEquals(1, request.messages().size());
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(new AiMessage(AiRole.USER, "x")));
    }
}
