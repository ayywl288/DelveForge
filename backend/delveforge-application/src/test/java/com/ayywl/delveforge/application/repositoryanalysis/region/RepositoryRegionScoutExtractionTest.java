package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Region Scout 的 AI 边界：请求形状、解析、引用校验与目录载荷测量。 */
class RepositoryRegionScoutExtractionTest {

    private static final int MAX_SELECTED = 6;

    /** 确定性替身：记录收到的请求并返回给定内容。不访问任何 Provider。 */
    private static final class RecordingGateway implements AiGateway {

        private final String response;
        private AiRequest lastRequest;

        RecordingGateway(String response) {
            this.response = response;
        }

        @Override
        public String generate(AiRequest request) {
            this.lastRequest = request;
            return response;
        }
    }

    private static RepositoryRegionScoutExtraction extraction(AiGateway gateway) {
        return new RepositoryRegionScoutExtraction(gateway, new ObjectMapper(), MAX_SELECTED);
    }

    @Test
    void returnsValidatedSelectionInModelOrder() {
        RecordingGateway gateway = new RecordingGateway("""
                { "regionRefs": ["RR-2", "RR-1"] }
                """);

        RepositoryRegionSelection selection =
                extraction(gateway).scout(RegionFixtures.catalog());

        assertEquals(List.of("svc", "src"),
                selection.regions().stream().map(RepositoryRegion::pathPrefix).toList());
        assertEquals(RegionFixtures.REVISION, selection.analyzedRevision());
    }

    @Test
    void rejectsSelectionThatReferencesAnUnknownRegion() {
        RecordingGateway gateway = new RecordingGateway("""
                { "regionRefs": ["RR-7"] }
                """);

        assertThrows(AiGatewayException.class,
                () -> extraction(gateway).scout(RegionFixtures.catalog()));
    }

    @Test
    void rejectsMalformedModelOutput() {
        RecordingGateway gateway = new RecordingGateway("这不是 json");

        assertThrows(AiGatewayException.class,
                () -> extraction(gateway).scout(RegionFixtures.catalog()));
    }

    @Test
    void sendsOnlyRegionDescriptorsAndNoFileLevelDetail() {
        RecordingGateway gateway = new RecordingGateway("""
                { "regionRefs": ["RR-1"] }
                """);

        extraction(gateway).scout(RegionFixtures.catalog());

        List<AiMessage> messages = gateway.lastRequest.messages();
        assertEquals(2, messages.size());
        assertEquals(AiRole.SYSTEM, messages.get(0).role());
        assertEquals(AiRole.USER, messages.get(1).role());
        assertEquals(AiResponseFormat.JSON, gateway.lastRequest.responseFormat());

        String payload = messages.get(1).content();
        assertTrue(payload.contains("regionCatalog"));
        assertTrue(payload.contains("\"svc\""), "目录前缀是允许出现的粒度");
        assertTrue(payload.contains("RR-1"));
        // 文件级的路径与内容不得出现：Region 阶段看不到文件清单。
        assertFalse(payload.contains("order.go"));
        assertFalse(payload.contains("OrderController.java"));
        assertFalse(payload.contains("package "));
    }

    @Test
    void measuresTheSamePayloadItWouldSend() {
        RecordingGateway gateway = new RecordingGateway("""
                { "regionRefs": ["RR-1"] }
                """);
        RepositoryRegionScoutExtraction extraction = extraction(gateway);
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        int measured = extraction.catalogPayloadBytes(catalog);
        extraction.scout(catalog);

        String userMessage = gateway.lastRequest.messages().get(1).content();
        assertEquals(userMessage.getBytes(StandardCharsets.UTF_8).length, measured,
                "先量的字节数必须就是即将发出的那一份载荷");
        assertTrue(measured > 0);
    }

    @Test
    void rejectsNullCatalog() {
        RepositoryRegionScoutExtraction extraction =
                extraction(new RecordingGateway("{}"));

        assertThrows(IllegalArgumentException.class, () -> extraction.scout(null));
        assertThrows(IllegalArgumentException.class,
                () -> extraction.catalogPayloadBytes(null));
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionScoutExtraction(null, new ObjectMapper(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionScoutExtraction(
                        new RecordingGateway("{}"), null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionScoutExtraction(
                        new RecordingGateway("{}"), new ObjectMapper(), 0));
    }
}
