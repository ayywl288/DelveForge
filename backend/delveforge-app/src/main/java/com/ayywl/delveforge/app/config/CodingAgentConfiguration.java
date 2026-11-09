package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.AgentStartupContext;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.agent.DeliveryStore;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiRequest.Thinking;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.port.workspace.CandidateDeliveryPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.quickstart.QuickStartService;
import com.ayywl.delveforge.application.quickstart.QuickStartStore;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.infrastructure.agent.AgentInstanceLock;
import com.ayywl.delveforge.infrastructure.agent.AgentSandboxProperties;
import com.ayywl.delveforge.infrastructure.agent.DeepSeekTokenCounterAdapter;
import com.ayywl.delveforge.infrastructure.agent.DockerAgentSandbox;
import com.ayywl.delveforge.infrastructure.agent.SpringAiAgentModel;
import com.ayywl.delveforge.infrastructure.agent.SqliteAgentStore;
import com.ayywl.delveforge.infrastructure.agent.SqliteDeliveryStore;
import com.ayywl.delveforge.infrastructure.agent.SqliteQuickStartStore;
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
    public Thinking agentThinking(Environment environment) {
        return environment.getProperty("delveforge.agent.thinking", Thinking.class, Thinking.HIGH);
    }

    @Bean
    public AgentModelPort agentModelPort(DeepSeekProperties properties, AgentContextBudget budget, Thinking thinking) {
        return new SpringAiAgentModel(properties, budget.responseTokens(), thinking);
    }

    @Bean
    public DeepSeekTokenCounterAdapter agentTokenCounter(DeepSeekProperties properties, Thinking thinking) {
        return new DeepSeekTokenCounterAdapter(properties.model(), thinking);
    }

    @Bean
    public AgentContextBudget agentContextBudget(Environment environment) {
        return new AgentContextBudget(
                environment.getProperty("delveforge.agent.context-window-tokens", Integer.class, 1000000),
                environment.getProperty("delveforge.agent.response-tokens", Integer.class, 65536),
                environment.getProperty("delveforge.agent.compaction-threshold", Double.class, 0.90));
    }

    @Bean
    public AgentSandboxPort agentSandboxPort(AgentSandboxProperties properties, RepositorySecretPolicy secrets) {
        return new DockerAgentSandbox(properties, secrets);
    }

    @Bean
    public CodingAgentService codingAgentService(ExecutionPreparationState eligibility, AgentStore store,
            AgentModelPort model, AgentSandboxPort sandbox, RepositorySecretPolicy secrets,
            AgentInstanceLock instanceLock, Environment environment, AgentTokenCounter counter, AgentContextBudget budget,
            UserProfileRepository profiles, RepositoryProfileRepository repositories) {
        AgentProtocol.Limits defaults = AgentProtocol.Limits.defaults();
        // 只配置技术材料和单次操作限制；累计调用、tokens 与总时长不作执行配额。
        AgentProtocol.Limits limits = new AgentProtocol.Limits(
                limit(environment, "max-context-chars", defaults.maxContextChars()),
                limit(environment, "max-output-bytes", defaults.maxOutputBytes()),
                limit(environment, "max-patch-bytes", defaults.maxPatchBytes()),
                limit(environment, "max-file-bytes", defaults.maxFileBytes()),
                limit(environment, "max-candidate-bytes", defaults.maxCandidateBytes()),
                limit(environment, "max-files", defaults.maxFiles()),
                limit(environment, "check-seconds", defaults.checkSeconds()));
        return new CodingAgentService(new AgentStartupContext(profiles, repositories, secrets),
                eligibility, store, model, sandbox, secrets, limits, counter, budget);
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
            CandidateDeliveryPort workspace, ExecutionPreparationState eligibility, AgentInstanceLock instanceLock,
            QuickStartService quickStart) {
        return new CandidateDeliveryService(agents, store, workspace, eligibility, quickStart::afterDelivery);
    }

    @Bean
    public QuickStartStore quickStartStore(DataSource source) {
        return new SqliteQuickStartStore(source);
    }

    @Bean
    public QuickStartService quickStartService(QuickStartStore store, WorkspaceReadPort workspace,
            AiGateway ai, RepositorySecretPolicy secrets) {
        return new QuickStartService(store, workspace, ai, secrets);
    }

    @Bean
    public ApplicationRunner reconcileAgentRuns(CodingAgentService agent, CandidateDeliveryService delivery, QuickStartStore quickStart) {
        return arguments -> {
            quickStart.interruptGenerating();
            agent.reconcile();
            delivery.start();
        };
    }
}
