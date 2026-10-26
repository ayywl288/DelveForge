package com.ayywl.delveforge.application.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPatchCorrectionsTest {
    private static final String CONFLICT = "{\"ok\":false,\"result\":{\"classification\":\"PATCH_CONFLICT\",\"mutationNotStarted\":true}}";

    private AgentProtocol.ToolCall call(String id, String value) {
        return new AgentProtocol.ToolCall(id, "apply_patch", "{\"patch\":\"" + value + "\"}");
    }

    @Test
    void sameDecodedPatchIsStoppedBeforeExecutionWithoutPuttingBodyInException() {
        var corrections = new AgentPatchCorrections();
        corrections.record(call("one", "private-body"), CONFLICT);
        var failure = assertThrows(AgentPatchCorrectionException.class, () -> corrections.before(call("two", "private-body")));
        assertEquals("PATCH_REPEATED_CONFLICT", failure.code());
        assertFalse(failure.getMessage().contains("private-body"));
    }

    @Test
    void lineEndingCorrectionIsDifferentAndThreeUnresolvedConflictsStop() {
        var corrections = new AgentPatchCorrections();
        corrections.record(call("one", "line\\n"), CONFLICT);
        assertDoesNotThrow(() -> corrections.before(call("two", "line\\r\\n")));
        corrections.record(call("two", "line\\r\\n"), CONFLICT);
        assertEquals("PATCH_CORRECTION_LIMIT", assertThrows(AgentPatchCorrectionException.class,
                () -> corrections.record(call("three", "different"), CONFLICT)).code());
    }

    @Test
    void actualApplicationResetsCorrectionHistoryButUnprovenConflictIsNotRecoverable() {
        var corrections = new AgentPatchCorrections();
        corrections.record(call("one", "old"), CONFLICT);
        corrections.record(call("safe", "new"), "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\"}}");
        assertDoesNotThrow(() -> corrections.before(call("two", "old")));
        assertEquals("PATCH_OUTCOME_UNCERTAIN", assertThrows(AgentException.class, () -> corrections.record(call("three", "other"),
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_CONFLICT\"}}")).code());
    }

    @Test
    void exactEditConflictsUseDecodedContentAndCannotRepeatWithDifferentJsonOrdering() {
        var corrections = new AgentPatchCorrections();
        var first = new AgentProtocol.ToolCall("first", "edit_file",
                "{\"path\":\"source.java\",\"old_text\":\"private before\",\"new_text\":\"after\"}");
        corrections.record(first, CONFLICT);
        var repeated = new AgentProtocol.ToolCall("second", "edit_file",
                "{\"new_text\":\"after\",\"old_text\":\"private before\",\"path\":\"source.java\"}");
        assertEquals("PATCH_REPEATED_CONFLICT", assertThrows(AgentPatchCorrectionException.class,
                () -> corrections.before(repeated)).code());
    }

    @Test
    void invalidTechnicalMaterialBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentProtocol.Limits(4095, 1024, 1024,
                1024, 2048, 1,
                1, 0));
    }
}
