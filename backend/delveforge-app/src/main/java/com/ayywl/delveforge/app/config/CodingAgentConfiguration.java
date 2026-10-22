package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.DeliveryStore;
import com.ayywl.delveforge.application.port.workspace.CandidateDeliveryPort;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.infrastructure.agent.AgentInstanceLock;
import com.ayywl.delveforge.infrastructure.agent.AgentSandboxProperties;
import com.ayywl.delveforge.infrastructure.agent.DockerAgentSandbox;
import com.ayywl.delveforge.infrastructure.agent.SpringAiAgentModel;
import com.ayywl.delveforge.infrastructure.agent.SqliteAgentStore;
import com.ayywl.delveforge.infrastructure.agent.SqliteDeliveryStore;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;
import com.ayywl.delveforge.infrastructure.persistence.PersistenceProperties;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/** 装配 Agent 与确定性交付；M1–M3 继续使用既有文本 Gateway。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "delveforge.agent.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentSandboxProperties.class)
public class CodingAgentConfiguration {
    @Bean
    public AgentInstanceLock agentInstanceLock(PersistenceProperties properties) {
        return new AgentInstanceLock(properties.databaseFile());
    }

    @Bean
    public AgentStore agentStore(DataSource source) {
        return new SqliteAgentStore(source);
    }

    @Bean
    public AgentModelPort agentModelPort(DeepSeekProperties properties, Environment environment) {
        return new SpringAiAgentModel(properties, environment.getProperty("delveforge.agent.response-tokens", Integer.class, 8192));
    }

    @Bean
    public AgentSandboxPort agentSandboxPort(AgentSandboxProperties properties, RepositorySecretPolicy secrets) {
        return new DockerAgentSandbox(properties, secrets);
    }

    @Bean
    public CodingAgentService codingAgentService(ExecutionPreparationState eligibility, AgentStore store,
            AgentModelPort model, AgentSandboxPort sandbox, RepositorySecretPolicy secrets,
            AgentInstanceLock instanceLock, Environment environment) {
        AgentProtocol.Limits defaults = AgentProtocol.Limits.defaults();
        AgentProtocol.Limits limits = new AgentProtocol.Limits(
                limit(environment, "max-model-calls", defaults.maxModelCalls()),
                limit(environment, "max-tool-calls", defaults.maxToolCalls()),
                environment.getProperty("delveforge.agent.limits.max-tokens", Long.class, defaults.maxTokens()),
                limit(environment, "max-context-chars", defaults.maxContextChars()),
                limit(environment, "max-output-bytes", defaults.maxOutputBytes()),
                limit(environment, "max-patch-bytes", defaults.maxPatchBytes()),
                limit(environment, "max-file-bytes", defaults.maxFileBytes()),
                limit(environment, "max-candidate-bytes", defaults.maxCandidateBytes()),
                limit(environment, "max-files", defaults.maxFiles()),
                limit(environment, "wall-seconds", defaults.wallSeconds()),
                limit(environment, "check-seconds", defaults.checkSeconds()),
                limit(environment, "dependency-retries", defaults.dependencyRetries()));
        return new CodingAgentService(eligibility, store, model, sandbox, secrets, limits);
    }

    private static int limit(Environment environment, String name, int fallback) {
        return environment.getProperty("delveforge.agent.limits." + name, Integer.class, fallback);
    }

    @Bean
    public DeliveryStore deliveryStore(DataSource source, ExecutionPreparationState eligibility) {
        return new SqliteDeliveryStore(source, eligibility);
    }

    @Bean
    public CandidateDeliveryService candidateDeliveryService(AgentStore agents, DeliveryStore store,
            CandidateDeliveryPort workspace, ExecutionPreparationState eligibility, AgentInstanceLock instanceLock) {
        return new CandidateDeliveryService(agents, store, workspace, eligibility);
    }

    @Bean
    public ApplicationRunner reconcileAgentRuns(CodingAgentService agent, CandidateDeliveryService delivery) {
        return arguments -> {
            agent.reconcile();
            delivery.start();
        };
    }
}
