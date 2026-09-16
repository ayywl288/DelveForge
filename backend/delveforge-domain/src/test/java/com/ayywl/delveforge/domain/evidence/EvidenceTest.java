package com.ayywl.delveforge.domain.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EvidenceTest {

    @Test
    void keepsEvidenceValues() {
        Evidence evidence = new Evidence(
                EvidenceSourceType.USER_INPUT, "user-answer-1", "用户希望解决日常记账问题", 0.6, false);

        assertEquals(EvidenceSourceType.USER_INPUT, evidence.sourceType());
        assertEquals("user-answer-1", evidence.sourceRef());
        assertEquals("用户希望解决日常记账问题", evidence.claim());
        assertEquals(0.6, evidence.confidence());
        assertEquals(false, evidence.confirmed());
    }

    @Test
    void allowsAbsentConfidence() {
        Evidence evidence = new Evidence(
                EvidenceSourceType.REPOSITORY, "pom.xml", "项目使用 Spring Boot", null, true);

        assertNull(evidence.confidence());
    }

    @Test
    void rejectsMissingSourceType() {
        assertThrows(IllegalArgumentException.class,
                () -> new Evidence(null, "user-answer-1", "用户输入", null, true));
    }

    @Test
    void rejectsBlankSourceRef() {
        assertThrows(IllegalArgumentException.class,
                () -> new Evidence(EvidenceSourceType.USER_INPUT, "  ", "用户输入", null, true));
    }

    @Test
    void rejectsBlankClaim() {
        assertThrows(IllegalArgumentException.class,
                () -> new Evidence(EvidenceSourceType.USER_INPUT, "user-answer-1", "", null, true));
    }

    @Test
    void rejectsNonFiniteConfidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new Evidence(
                        EvidenceSourceType.USER_INPUT, "user-answer-1", "用户输入",
                        Double.NaN, false));
    }
}
