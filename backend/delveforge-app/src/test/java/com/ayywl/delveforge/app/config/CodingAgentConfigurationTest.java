package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodingAgentConfigurationTest {
    @Test
    void uses500KDefaultAndKeepsExistingContextOverrideAndOtherLimits() {
        var configuration = new CodingAgentConfiguration();
        for (int budget : new int[] {500000, 24000}) {
            var environment = new MockEnvironment();
            if (budget != 500000) {
                environment.setProperty("delveforge.agent.limits.max-context-chars", Integer.toString(budget));
            }
            var service = configuration.codingAgentService(null, null, null, null,
                    new DeterministicRepositorySecretPolicy(), null, environment);
            var limits = (AgentProtocol.Limits) ReflectionTestUtils.getField(service, "limits");
            assertEquals(budget, limits.maxContextChars());
            assertEquals(24000, limits.maxOutputBytes());
            assertEquals(131072, limits.maxPatchBytes());
            assertEquals(262144, limits.maxFileBytes());
            assertEquals(2097152, limits.maxCandidateBytes());
        }
    }
}
