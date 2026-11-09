package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.AgentStartupContext;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import static org.mockito.Mockito.mock;

/** 合成模型的确定性容量单位；真实分词器由官方样例独立覆盖。 */
final class TestAgentServices {
    static AgentStartupContext startup() {
        return new AgentStartupContext(mock(UserProfileRepository.class), mock(RepositoryProfileRepository.class),
                new DeterministicRepositorySecretPolicy());
    }

    static CodingAgentService create(ExecutionPreparationState state, AgentStore store, AgentModelPort model,
            AgentSandboxPort sandbox, RepositorySecretPolicy secrets, AgentProtocol.Limits limits) {
        return new CodingAgentService(startup(), state, store, model, sandbox, secrets, limits,
                (messages, tools) -> messages.stream().mapToLong(message -> message.text().length()
                        + message.calls().stream().mapToInt(call -> call.arguments().length()).sum()).sum(),
                new AgentContextBudget(limits.maxContextChars() + 1, 1, 0.90));
    }
}
