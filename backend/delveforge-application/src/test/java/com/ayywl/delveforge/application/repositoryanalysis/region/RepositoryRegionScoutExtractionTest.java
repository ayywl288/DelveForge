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

/** Region Scout 的 AI 边界：请求形状、解析、引用校验、载荷测量与不可绕过的字节守卫。 */
class RepositoryRegionScoutExtractionTest {

    private static final RegionNavigationLimits LIMITS =
            new RegionNavigationLimits(65_536, 6);

    /** 确定性替身：记录收到的请求并返回给定内容。不访问任何 Provider。 */
    private static final class RecordingGateway implements AiGateway {

        private final String response;
        private AiRequest lastRequest;
        private int calls;

        RecordingGateway(String response) {
            this.response = response;
        }

        @Override
        public String generate(AiRequest request) {
            this.lastRequest = request;
            this.calls++;
            return response;
        }
    }

    private static RepositoryRegionScoutExtraction extraction(AiGateway gateway) {
        return extraction(gateway, LIMITS);
    }

    private static RepositoryRegionScoutExtraction extraction(AiGateway gateway,
                                                             RegionNavigationLimits limits) {
        return new RepositoryRegionScoutExtraction(gateway, new ObjectMapper(), limits);
    }

    @Test
    void returnsValidatedSelectionInModelOrder() {
        RecordingGateway gateway = new RecordingGateway(
                RegionFixtures.selectionResponse(RegionFixtures.catalog(), 2, 1));

        RepositoryRegionSelection selection =
                extraction(gateway).scout(RegionFixtures.catalog());

        assertEquals(List.of("svc", "src"),
                selection.regions().stream().map(RepositoryRegion::pathPrefix).toList());
        assertEquals(RegionFixtures.REVISION, selection.analyzedRevision());
    }

    @Test
    void rejectsSelectionThatReferencesAnUnknownRegion() {
        RecordingGateway gateway = new RecordingGateway(
                "{\"regionRefs\":[\"RR-00000000-7\"]}");

        assertThrows(AiGatewayException.class,
                () -> extraction(gateway).scout(RegionFixtures.catalog()));
    }

    @Test
    void rejectsSelectionThatReferencesAnotherCatalogsRegion() {
        // 用另一份目录的引用（内容不同 → 作用域不同）来冒充本次调用
        RepositoryRegionCatalog other = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION,
                List.of(RegionFixtures.tree().region("svc").orElseThrow()));
        RecordingGateway gateway = new RecordingGateway(
                "{\"regionRefs\":[\"" + other.entries().get(0).reference().value() + "\"]}");

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
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();
        RecordingGateway gateway = new RecordingGateway(
                RegionFixtures.selectionResponse(catalog, 1));

        extraction(gateway).scout(catalog);

        List<AiMessage> messages = gateway.lastRequest.messages();
        assertEquals(2, messages.size());
        assertEquals(AiRole.SYSTEM, messages.get(0).role());
        assertEquals(AiRole.USER, messages.get(1).role());
        assertEquals(AiResponseFormat.JSON, gateway.lastRequest.responseFormat());

        String payload = messages.get(1).content();
        assertTrue(payload.contains("regionCatalog"));
        assertTrue(payload.contains("\"svc\""), "目录前缀是允许出现的粒度");
        assertTrue(payload.contains(catalog.entries().get(0).reference().value()),
                "清单里出现的编号就是本次调用的引用");
        // 文件级的路径与内容不得出现：Region 阶段看不到文件清单。
        assertFalse(payload.contains("order.go"));
        assertFalse(payload.contains("OrderController.java"));
        assertFalse(payload.contains("package "));
    }

    @Test
    void measuresTheSamePayloadItWouldSend() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();
        RecordingGateway gateway = new RecordingGateway(
                RegionFixtures.selectionResponse(catalog, 1));
        RepositoryRegionScoutExtraction extraction = extraction(gateway);

        int measured = extraction.catalogPayloadBytes(catalog);
        extraction.scout(catalog);

        String userMessage = gateway.lastRequest.messages().get(1).content();
        assertEquals(userMessage.getBytes(StandardCharsets.UTF_8).length, measured,
                "先量的字节数必须就是即将发出的那一份载荷");
        assertTrue(measured > 0);
    }

    /**
     * 字节守卫必须在调用边界内执行：超限的目录**不可能**产生一次模型调用。
     *
     * <p>旧实现只在编排层提供测量，绕过去仍会真的调用模型——那里没有守卫，只有自觉。
     */
    @Test
    void oversizedCatalogFailsWithoutCallingTheGateway() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();
        RepositoryRegionScoutExtraction extraction = extraction(
                new RecordingGateway(RegionFixtures.selectionResponse(catalog, 1)),
                LIMITS);

        int payloadBytes = extraction.catalogPayloadBytes(catalog);
        assertTrue(payloadBytes > 1);

        RecordingGateway gateway = new RecordingGateway(
                RegionFixtures.selectionResponse(catalog, 1));
        RepositoryRegionScoutExtraction tooSmall = extraction(gateway,
                new RegionNavigationLimits(payloadBytes - 1, 6));

        assertThrows(RepositoryRegionCatalogTooLargeException.class,
                () -> tooSmall.scout(catalog));
        assertEquals(0, gateway.calls,
                "目录超限时不得调用 Gateway，也不得留下任何部分结果");
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
                () -> new RepositoryRegionScoutExtraction(null, new ObjectMapper(), LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionScoutExtraction(
                        new RecordingGateway("{}"), null, LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionScoutExtraction(
                        new RecordingGateway("{}"), new ObjectMapper(), null));
    }
}
