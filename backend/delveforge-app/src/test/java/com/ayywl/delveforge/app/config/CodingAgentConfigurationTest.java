package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodingAgentConfigurationTest {
    @Test
    void tokenWindowAndActualAgentResponseReserveAreIndependentlyConfigurable() {
        var configuration = new CodingAgentConfiguration();
        var defaults = configuration.agentContextBudget(new MockEnvironment());
        assertEquals(8192, defaults.responseTokens());
        assertEquals(892628, defaults.triggerTokens());
        var environment = new MockEnvironment()
                .withProperty("delveforge.agent.context-window-tokens", "20000")
                .withProperty("delveforge.agent.response-tokens", "4000")
                .withProperty("delveforge.agent.compaction-threshold", "0.85")
                .withProperty("delveforge.ai.deepseek.max-tokens", "98304");
        var budget = configuration.agentContextBudget(environment);
        assertEquals(13600, budget.triggerTokens());
        assertEquals(12000, budget.targetTokens());
    }

    @Test
    void uses500KDefaultAndKeepsExistingContextOverrideAndOtherLimits() {
        var configuration = new CodingAgentConfiguration();
        for (int budget : new int[] {500000, 24000}) {
            var environment = new MockEnvironment();
            if (budget != 500000) {
                environment.setProperty("delveforge.agent.limits.max-context-chars", Integer.toString(budget));
            }
            var service = configuration.codingAgentService(null, null, null, null,
                    new DeterministicRepositorySecretPolicy(), null, environment, (messages, tools) -> 0, configuration.agentContextBudget(environment));
            var limits = (AgentProtocol.Limits) ReflectionTestUtils.getField(service, "limits");
            assertEquals(budget, limits.maxContextChars());
            assertEquals(24000, limits.maxOutputBytes());
            assertEquals(131072, limits.maxPatchBytes());
            assertEquals(262144, limits.maxFileBytes());
            assertEquals(2097152, limits.maxCandidateBytes());
        }
    }
}
