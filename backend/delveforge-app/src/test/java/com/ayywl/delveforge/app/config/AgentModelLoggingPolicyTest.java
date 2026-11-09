package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTools;
import com.ayywl.delveforge.infrastructure.agent.SpringAiAgentModel;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentModelLoggingPolicyTest {
    @Test
    void providerProtocolFailureDoesNotLogPromptOrReasoning() throws Exception {
        var configuration = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).getFirst();
        Object configured = configuration.getProperty("logging.level.org.springframework.ai.deepseek.DeepSeekChatModel");
        assertEquals("ERROR", configured);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = logger.getLevel();
        boolean additive = logger.isAdditive();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setAdditive(false);
        server.start();
        try {
            Duration timeout = Duration.ofSeconds(3);
            var model = new SpringAiAgentModel(new DeepSeekProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                    "fixture-only-key", "fixture-model", timeout, false, timeout, 8192, 1048576, 65536, 8388608), 8192);
            var history = List.of(AgentProtocol.Message.text("user", "synthetic-private-prompt-marker"),
                    new AgentProtocol.Message("assistant", "visible", List.of(), null, null, "private-reason-marker"),
                    AgentProtocol.Message.text("user", "Retry"));
            logger.setLevel(Level.INFO);
            assertThrows(AgentModelFailure.class, () -> model.call(history, AgentTools.definitions()));
            assertFalse(captured.list.stream().anyMatch(e -> e.getFormattedMessage().contains("private")));
            captured.list.clear();
            logger.setLevel(Level.valueOf(configured.toString()));
            var failure = assertThrows(AgentModelFailure.class, () -> model.call(history, AgentTools.definitions()));
            assertEquals(AgentModelFailure.Reason.RESULT_MISSING, failure.reason());
            assertFalse(captured.list.stream().anyMatch(e -> e.getFormattedMessage().contains("synthetic-private-prompt-marker")));
        } finally {
            server.stop(0);
            logger.detachAppender(captured);
            captured.stop();
            logger.setLevel(previous);
            logger.setAdditive(additive);
        }
    }
}
