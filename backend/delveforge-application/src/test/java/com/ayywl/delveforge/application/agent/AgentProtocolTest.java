package com.ayywl.delveforge.application.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentProtocolTest {
    @Test
    void invalidTechnicalMaterialBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentProtocol.Limits(4095, 1024, 1024,
                1024, 2048, 1, 1));
    }
}
