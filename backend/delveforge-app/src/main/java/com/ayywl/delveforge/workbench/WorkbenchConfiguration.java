package com.ayywl.delveforge.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ProjectFiles;
import com.ayywl.delveforge.application.workbench.ProjectTools;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchModels;
import com.ayywl.delveforge.infrastructure.workbench.model.SpringWorkbenchModels;
import com.ayywl.delveforge.infrastructure.agent.AgentInstanceLock;
import com.ayywl.delveforge.infrastructure.workbench.DockerDevelopmentEnvironment;
import com.ayywl.delveforge.infrastructure.workbench.DockerProjectTools;
import com.ayywl.delveforge.infrastructure.workbench.GatewayConnection;
import com.ayywl.delveforge.infrastructure.workbench.ProjectWorkcopies;
import com.ayywl.delveforge.infrastructure.workbench.SqliteWorkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Configuration
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
    ProjectTools projectTools(Path root, ObjectMapper json) {
        return new DockerProjectTools(root, json);
    }

    @Bean
    Workbench workbench(DataSource database, ObjectMapper json, WorkbenchModels models,
            @Value("${delveforge.workbench.max-concurrent-runs:2}") int capacity) {
        return new SqliteWorkbench(database, json, capacity, models);
    }

    @Bean
    ProjectFiles workcopies(Path root) throws IOException {
        return new ProjectWorkcopies(root.resolve("projects"));
    }

    @Bean
    DockerDevelopmentEnvironment developmentEnvironment(
            Path root,
            ObjectMapper json,
            @Value("${delveforge.workbench.command-seconds:300}") int timeout,
            @Value("${delveforge.workbench.project-cpus:2}") int cpu,
            @Value("${delveforge.workbench.project-memory-mib:3072}") int memory,
            @Value("${delveforge.workbench.project-pids:768}") int pids) {
        return new DockerDevelopmentEnvironment(root, json, timeout, cpu, memory, pids);
    }

    @Bean
    AgentContextBudget contextBudget(
            @Value("${delveforge.workbench.model.context-window-tokens}") int window,
            @Value("${delveforge.workbench.model.response-tokens}") int output,
            @Value("${delveforge.workbench.model.compaction-threshold}") double threshold) {
        return new AgentContextBudget(window, output, threshold);
    }

    @Bean
    ObjectMapper workbenchJson() {
        return new ObjectMapper();
    }

    @Bean
    WorkbenchModels models(DataSource database, ObjectMapper json, Path root, Environment environment,
            AgentContextBudget ceiling, @Value("${delveforge.workbench.model.timeout}") Duration timeout,
            @Value("${delveforge.workbench.model.max-request-bytes}") int requestBytes,
            @Value("${delveforge.workbench.model.max-response-bytes}") int responseBytes) {
        String model = environment.getProperty("delveforge.ai.deepseek.model", "deepseek-flash");
        String effort = environment.getProperty("delveforge.agent.thinking", "high").toLowerCase(Locale.ROOT);
        var choice = new WorkbenchModels.Choice("deepseek", model, "disabled".equals(effort) ? "off" : effort);
        return new SpringWorkbenchModels(database, json, root, choice, ceiling, timeout, requestBytes, responseBytes,
                name -> {
                    if ("DEEPSEEK_API_KEY".equals(name)) {
                        // V7 的自定义网关凭据不得转发至官方端点。
                        String base = environment.getProperty("delveforge.ai.deepseek.base-url", "https://api.deepseek.com");
                        return Set.of("https://api.deepseek.com", "https://api.deepseek.com/v1")
                                .contains(base.replaceAll("/+$", ""))
                                ? environment.getProperty("delveforge.ai.deepseek.api-key", System.getenv(name)) : null;
                    }
                    String value = System.getenv(name);
                    return value == null && "GEMINI_API_KEY".equals(name) ? System.getenv("GOOGLE_API_KEY") : value;
                });
    }

    @Bean
    ConversationAgent conversationAgent(Workbench store, ProjectFiles files, DockerDevelopmentEnvironment environment,
            WorkbenchModels models, ObjectMapper json,
            @Value("${delveforge.workbench.requests-per-run:${delveforge.workbench.requests-per-continuation:96}}")
            int allowance) {
        return new ConversationAgent(store, files, environment, models, json, allowance);
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
