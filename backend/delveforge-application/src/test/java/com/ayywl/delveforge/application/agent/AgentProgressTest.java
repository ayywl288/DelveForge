package com.ayywl.delveforge.application.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentProgressTest {
    private final AgentProtocol.ToolCall read = new AgentProtocol.ToolCall("read", "read_file", "{\"path\":\"A.java\"}");

    @Test
    void identicalObservationsStopEvenWhenDurationsChange() {
        AgentProgress progress = new AgentProgress();
        progress.record(read, "{\"ok\":true,\"durationMs\":1}", false);
        progress.record(read, "{\"durationMs\":2,\"ok\":true}", false);
        assertEquals("NO_PROGRESS", assertThrows(AgentException.class,
                () -> progress.record(read, "{\"ok\":true,\"durationMs\":3}", false)).code());
    }

    @Test
    void successfulMutationResetsRepeatedObservations() {
        AgentProgress progress = new AgentProgress();
        for (int index = 0; index < 100; index++) {
            progress.record(read, "{\"ok\":true}", false);
            progress.record(read, "{\"ok\":true}", false);
            progress.record(new AgentProtocol.ToolCall("edit", "edit_file", "{}"), "{\"ok\":true}", true);
        }
    }

    @Test
    void newFactsContinueWithoutACumulativeReadLimit() {
        AgentProgress progress = new AgentProgress();
        for (int index = 0; index < 200; index++) {
            progress.record(read, "{\"ok\":true,\"result\":{\"content\":\"new fact " + index + "\"}}", false);
        }
    }
}
