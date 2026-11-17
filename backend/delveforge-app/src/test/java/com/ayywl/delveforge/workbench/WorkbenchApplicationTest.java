package com.ayywl.delveforge.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkbenchApplicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path directory;

    @Test
    void supportedApiReopensItsProjectsAndLeavesTheRetiredDatabaseUntouched() throws Exception {
        Path retired = directory.resolve("delveforge.db");
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + retired);
                var sql = database.createStatement()) {
            sql.execute("CREATE TABLE user_data(value TEXT)");
            sql.execute("INSERT INTO user_data VALUES('original')");
        }
        byte[] historical = Files.readAllBytes(retired);
        String conversation;
        Path file;
        try (var client = HttpClient.newHttpClient(); var app = start(retired)) {
            URI base = base(app);
            assertEquals("UP", JSON.readTree(get(client, base, "/api/workbench/health").body()).path("status").asText());
            var catalog = get(client, base, "/api/workbench/models");
            assertEquals(8, JSON.readTree(catalog.body()).path("providers").size());
            assertFalse(catalog.body().contains("synthetic-test-secret"));
            String project = JSON.readTree(post(client, base, "/api/workbench/projects",
                    Map.of("name", "Current project")).body()).path("id").asText();
            conversation = JSON.readTree(post(client, base, "/api/workbench/projects/" + project + "/conversations",
                    Map.of("title", "Current conversation")).body()).path("id").asText();
            var projects = JSON.readTree(get(client, base, "/api/workbench/projects").body());
            file = Path.of(projects.get(0).path("workcopy").asText()).resolve("existing.txt");
            Files.writeString(file, "user file");
            var choice = Map.of("provider", "deepseek", "model", "deepseek-flash", "effort", "off");
            assertEquals(200, post(client, base, "/api/workbench/conversations/" + conversation + "/model", choice).statusCode());
            for (String path : new String[]{"/api/projects", "/api/evolution-plans", "/api/user-profiles"}) {
                assertEquals(404, get(client, base, path).statusCode(), path);
            }
        }
        try (var client = HttpClient.newHttpClient(); var restarted = start(retired)) {
            var feed = JSON.readTree(get(client, base(restarted), "/api/workbench/conversations/" + conversation).body());
            assertEquals("off", feed.at("/model/effort").asText());
            assertEquals("user file", Files.readString(file));
            assertArrayEquals(historical, Files.readAllBytes(retired));
        }
    }

    private ServletWebServerApplicationContext start(Path retired) {
        return (ServletWebServerApplicationContext) SpringApplication.run(WorkbenchApplication.class,
                "--server.port=0", "--delveforge.workbench.directory=" + directory.resolve("workbench"),
                "--delveforge.persistence.database-file=" + retired, "--delveforge.ai.deepseek.api-key=synthetic-test-secret",
                "--logging.file.name=");
    }

    private static URI base(ServletWebServerApplicationContext app) {
        return URI.create("http://127.0.0.1:" + app.getWebServer().getPort());
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, URI base, String path, Map<String, String> body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
