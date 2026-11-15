package com.ayywl.delveforge.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;
import com.ayywl.delveforge.application.port.ai.AiRequest.Thinking;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.ProjectFiles;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.infrastructure.agent.AgentInstanceLock;
import com.ayywl.delveforge.infrastructure.agent.DeepSeekTokenCounterAdapter;
import com.ayywl.delveforge.infrastructure.agent.SpringAiAgentModel;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;
import com.ayywl.delveforge.infrastructure.workbench.DockerDevelopmentEnvironment;
import com.ayywl.delveforge.infrastructure.workbench.GatewayConnection;
import com.ayywl.delveforge.infrastructure.workbench.ProjectWorkcopies;
import com.ayywl.delveforge.infrastructure.workbench.SqliteWorkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(DeepSeekProperties.class)
public class WorkbenchConfiguration {
    @Bean
    Path workbenchRoot(
            @Value("${delveforge.workbench.directory:${user.dir}/data/workbench}") String directory)
            throws IOException {
        return Files.createDirectories(Path.of(directory)).toRealPath();
    }

    @Bean
    AgentInstanceLock workbenchLock(Path root) {
        return new AgentInstanceLock(root.resolve("control.db"));
    }

    @Bean
    DataSource workbenchDatabase(Path root, AgentInstanceLock lock) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        var source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + root.resolve("control.db"));
        Flyway.configure().dataSource(source).locations("classpath:db/workbench").load().migrate();
        return source;
    }

    @Bean
    Workbench workbench(DataSource database, ObjectMapper json) {
        return new SqliteWorkbench(database, json);
    }

    @Bean
    ProjectFiles workcopies(Path root) throws IOException {
        return new ProjectWorkcopies(root.resolve("projects"));
    }

    @Bean
    DockerDevelopmentEnvironment developmentEnvironment(
            Path root,
            ObjectMapper json,
            @Value("${delveforge.workbench.command-seconds:300}") int timeout) {
        return new DockerDevelopmentEnvironment(root, json, timeout);
    }

    @Bean
    AgentContextBudget contextBudget(
            @Value("${delveforge.agent.context-window-tokens}") int window,
            @Value("${delveforge.agent.response-tokens}") int output,
            @Value("${delveforge.agent.compaction-threshold}") double threshold) {
        return new AgentContextBudget(window, output, threshold);
    }

    @Bean
    AgentTokenCounter tokenCounter(
            DeepSeekProperties properties,
            @Value("${delveforge.agent.thinking}") Thinking thinking) {
        return new DeepSeekTokenCounterAdapter(properties.model(), thinking);
    }

    @Bean
    AgentModelPort model(
            DeepSeekProperties properties,
            AgentContextBudget budget,
            @Value("${delveforge.agent.thinking}") Thinking thinking) {
        return new SpringAiAgentModel(properties, budget.responseTokens(), thinking);
    }

    @Bean
    ConversationAgent conversationAgent(
            Workbench store,
            ProjectFiles files,
            DockerDevelopmentEnvironment environment,
            AgentModelPort model,
            AgentTokenCounter counter,
            AgentContextBudget budget,
            ObjectMapper json,
            @Value("${delveforge.workbench.requests-per-continuation:24}") int allowance) {
        if (allowance < 1) {
            throw new IllegalArgumentException("每段模型请求预算必须为正");
        }
        return new ConversationAgent(
                store,
                files,
                environment,
                model,
                new ConversationContext(store, model, counter, budget),
                json,
                allowance);
    }

    @Bean
    GatewayConnection gateway(
            Path root, DockerDevelopmentEnvironment env, Workbench store, ObjectMapper json) {
        return new GatewayConnection(root, env, store, json);
    }

    @Bean
    ApplicationRunner recoverWorkbench(ConversationAgent agent, GatewayConnection gateway) {
        return args -> {
            agent.startup();
            gateway.start();
        };
    }
}
