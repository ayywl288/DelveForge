package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合成不兼容形状覆盖真实提取和策略；不声称重建未留存的 Provider 原始响应。 */
class ProfileExtractionCompatibilityTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void missingAuxiliaryClaimsUseAnExactBoundedQuoteFromThisTurnOnly() {
        UserProfile profile = UserProfile.create(new UserProfileId("bounded-source"));
        String input = "  我只做本地工具。" + "补充说明".repeat(800) + "  ";
        var extraction = new ProfileExtraction(request -> "{\"constraints\":[\"仅本地使用\"]}", json);
        extraction.applyTurnTo(profile, input, List.of(new DiscoveryContext.Message("user", "历史中说会 Rust")));
        assertEquals(3, profile.revision());
        assertEquals(1, profile.evidence().size());
        var evidence = profile.evidence().getFirst();
        assertEquals(EvidenceSourceType.USER_INPUT, evidence.sourceType());
        assertEquals(input, evidence.sourceRef());
        assertEquals(input.strip().substring(0, 2048), evidence.claim());
        assertTrue(input.contains(evidence.claim()));
        assertFalse(evidence.confirmed());
        assertFalse(evidence.claim().contains("Rust"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"interests", "behaviors", "painPoints", "technicalCapabilities", "projectGoals", "constraints"})
    void malformedOrExcessiveSectionsStillFailWithoutMutationOrRetry(String section) throws Exception {
        for (Object value : List.of("不是数组", List.of(42), List.of(" "), List.of("x".repeat(2049)),
                java.util.Collections.nCopies(65, "过多条目"))) {
            UserProfile profile = UserProfile.create(new UserProfileId("invalid-section"));
            AtomicInteger calls = new AtomicInteger();
            String output = json.writeValueAsString(java.util.Map.of(section, value,
                    "evidenceClaims", List.of(java.util.Map.of("ignored", true))));
            var extraction = new ProfileExtraction(request -> {
                calls.incrementAndGet();
                return output;
            }, json);
            assertThrows(AiGatewayException.class,
                    () -> extraction.applyTurnTo(profile, "本轮真实输入", List.of()));
            assertEquals(1, calls.get());
            assertEquals(1, profile.revision());
            assertTrue(profile.evidence().isEmpty());
        }
    }

    @Test
    void excessiveAuxiliaryClaimsAreBoundedWithoutRejectingValidSections() throws Exception {
        UserProfile profile = UserProfile.create(new UserProfileId("bounded-claims"));
        List<String> descriptions = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> "可选说明 " + index).toList();
        String output = json.writeValueAsString(java.util.Map.of("technicalCapabilities", List.of("Java"),
                "evidenceClaims", descriptions));
        new ProfileExtraction(request -> output, json).applyTurnTo(profile, "会 Java", List.of());
        assertEquals(List.of("Java"), profile.technicalCapabilities());
        assertEquals(64, profile.evidence().size());
        assertTrue(profile.evidence().stream().allMatch(evidence -> "会 Java".equals(evidence.sourceRef())
                && !evidence.confirmed()));
    }

    @Test
    void boundedQuotationKeepsUnicodeCharactersWhole() {
        UserProfile profile = UserProfile.create(new UserProfileId("unicode-source"));
        String input = "本".repeat(2047) + "📚笔记";
        new ProfileExtraction(request -> "{\"interests\":[\"笔记\"]}", json)
                .applyTurnTo(profile, input, List.of());
        assertEquals(input.substring(0, 2047), profile.evidence().getFirst().claim());
        assertEquals(input, profile.evidence().getFirst().sourceRef());
    }
}
