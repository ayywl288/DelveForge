package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchModels;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Choice;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;
import com.ayywl.delveforge.domain.workbench.RunState;
import com.ayywl.delveforge.infrastructure.workbench.SqliteWorkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunModelPersistenceTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final Choice deepseek = new Choice("deepseek", "deepseek-flash", "high");
    final Choice claude = new Choice("anthropic", "claude-opus-4-6", "max");
    final Choice qwen = new Choice("qwen", "qwen3.5-plus", "off");
    final Map<String, String> env = new HashMap<>(Map.of("DEEPSEEK_API_KEY", "deepseek-test",
            "ANTHROPIC_API_KEY", "claude-test", "DASHSCOPE_API_KEY", "qwen-test",
            "DELVEFORGE_CREDENTIAL_MASTER_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));

    private SQLiteDataSource database() {
        var db = new SQLiteDataSource();
        db.setUrl("jdbc:sqlite:" + root.resolve("history.db"));
        return db;
    }

    private void migrate(String version) {
        Flyway.configure().dataSource(database()).locations("classpath:db/workbench").target(version).load().migrate();
    }

    private SpringWorkbenchModels models() {
        return new SpringWorkbenchModels(database(), json, root, deepseek, new AgentContextBudget(1000000, 65536, .90),
                Duration.ofSeconds(5), 1000000, 1000000, env::get);
    }

    private Workbench open(int capacity) {
        return new SqliteWorkbench(database(), json, capacity, models());
    }

    private String conversation(Workbench store) {
        String project = store.beginProject("Test", null);
        store.projectResult(project, root.toString(), null);
        return store.conversation(project, "Test");
    }

    @Test
    void queuePauseResumeAndTerminalSwitchUseTheFrozenConfigurationPerConversation() {
        migrate("8");
        var store = open(2);
        String a = conversation(store), b = conversation(store), c = conversation(store);
        store.selectModel(b, claude);
        store.selectModel(c, qwen);
        String first = store.receive(a, "1", "test a", 10).runId();
        String second = store.receive(b, "2", "test b", 10).runId();
        String queued = store.receive(c, "3", "test c", 10).runId();
        assertEquals(2, store.owners().size());
        assertEquals(RunState.QUEUED, store.run(queued).state());
        assertEquals(deepseek, store.runModel(first).choice());
        assertEquals(claude, store.runModel(second).choice());
        var original = store.runModel(queued);
        assertEquals(qwen, original.choice());
        assertThrows(WorkbenchRejection.class, () -> store.selectModel(c, claude));
        assertThrows(WorkbenchRejection.class, () -> store.selectModel(a, qwen));
        store.state(first, RunState.FAILED, "test");
        assertEquals(queued, store.claimNext().orElseThrow());
        assertEquals(original, open(2).runModel(queued));
        store.state(queued, RunState.PAUSED, "test");
        assertThrows(WorkbenchRejection.class, () -> store.selectModel(c, deepseek));
        assertEquals(queued, store.start(c, 10));
        assertEquals(original, store.runModel(queued));
        store.state(queued, RunState.PAUSED, "test");
        store.stop(queued);
        store.selectModel(c, new Choice("qwen", "qwen3.5-plus", "on"));
        var changed = store.receive(c, "4", "continue", 10).runId();
        assertEquals("on", store.runModel(changed).choice().effort());
        assertEquals(original, store.runModel(queued));
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                new JdbcTemplate(database()).update("UPDATE run SET model_config='{}' WHERE id=?", queued));
    }

    @Test
    void savedKeysAreEncryptedAndCannotSubstituteForBoundCredentials() {
        migrate("8");
        var models = models();
        String secret = "synthetic-only-private-key";
        models.save("deepseek", "global", "", secret);
        var bound = models.freeze(deepseek);
        var jdbc = new JdbcTemplate(database());
        var ciphertext = jdbc.queryForObject("SELECT ciphertext FROM workbench_provider", byte[].class);
        assertFalse(new String(ciphertext, java.nio.charset.StandardCharsets.ISO_8859_1).contains(secret));
        var credentials = new ProviderCredentials(jdbc, env::get);
        assertEquals(secret, credentials.resolve(bound));
        assertEquals(bound, models().freeze(deepseek));
        assertFalse(bound.toString().contains(secret));
        assertThrows(WorkbenchRejection.class, () -> models.save("deepseek", "https://example.com", "", secret));
        assertThrows(WorkbenchRejection.class, () -> models.save("qwen", "tokyo-workspace", "x.example.com", secret));
        assertThrows(WorkbenchRejection.class, () -> models.freeze(new Choice("qwen", "qwen3.5-plus", "max")));
        models.save("deepseek", "global", "", "replacement-key");
        assertThrows(AgentException.class, () -> credentials.resolve(bound));
        var replaced = models.freeze(deepseek);
        models.delete("deepseek");
        assertThrows(AgentException.class, () -> credentials.resolve(replaced));
        var environmentBound = models.freeze(deepseek);
        env.put("DEEPSEEK_API_KEY", "changed-environment-key");
        assertThrows(AgentException.class, () -> credentials.resolve(environmentBound));
        env.remove("DEEPSEEK_API_KEY");
        assertThrows(WorkbenchRejection.class, () -> models.freeze(deepseek));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completePartsSurviveRestartAndCrossProviderHistoryUsesReceipts(boolean fromClaude) throws Exception {
        migrate("8");
        var store = open(2);
        String conversation = conversation(store);
        var producer = fromClaude ? claude : deepseek;
        var target = fromClaude ? deepseek : qwen;
        store.selectModel(conversation, producer);
        String first = store.receive(conversation, "1", "Keep the original requirement", 8).runId();
        store.next(first);
        var tool = new AssistantMessage.ToolCall("call-1", "function", "Read", "{\"path\":\"README\"}");
        var stored = SpringMessages.store(producer, List.of(
                new ReasoningPart("hidden", null, fromClaude ? new OpaquePayload("anthropic", "signature",
                        "private-signature") : null, Map.of("origin", "contract")),
                ToolCallPart.of(tool), new UnknownPart(producer.provider(), "future", "{\"private\":1}", null, Map.of())));
        store.assistant(first, new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall(tool.id(), tool.name(),
                tool.arguments())), 2L, null, null, stored));
        store.state(first, RunState.PAUSED, "test");
        assertThrows(WorkbenchRejection.class, () -> store.stop(first));
        store.start(conversation, 8);
        store.result(store.pending(first).getFirst(), "{\"result\":\"real receipt\"}", 0, false);
        store.assistant(first, new AgentProtocol.Reply("done", List.of(), 1L));
        var restarted = open(2);
        var reloaded = restarted.next(first).context().records().stream().map(Workbench.Entry::message)
                .filter(m -> m.stored() != null).findFirst().orElseThrow();
        assertEquals(SpringMessages.parts(stored), SpringMessages.parts(reloaded.stored()));
        assertFalse(json.writeValueAsString(restarted.timeline(conversation)).contains("private"));
        restarted.selectModel(conversation, target);
        String next = restarted.receive(conversation, "2", "Continue with the receipt", 8).runId();
        var frozen = restarted.runModel(next);
        var binding = models().bind(frozen);
        var safe = new ConversationContext(restarted, binding.model(), binding.counter(), frozen.budget()).build(restarted.next(next));
        assertTrue(safe.stream().anyMatch(m -> m.text().contains("Keep the original requirement")));
        assertTrue(safe.stream().anyMatch(m -> m.text().contains("Continue with the receipt")));
        assertTrue(safe.getFirst().text().contains("[#"));
        assertTrue(safe.stream().allMatch(m -> m.stored() == null && m.calls().isEmpty()));
        assertTrue(restarted.timeline(conversation).toString().contains("real receipt"));
    }

    @Test
    void smallerContextPausesBeforeTruncatingNewUserRequirements() throws Exception {
        migrate("8");
        var cache = json.readTree(getClass().getResourceAsStream("/workbench-models.json"));
        var limits = (com.fasterxml.jackson.databind.node.ObjectNode) cache.at("/alibaba/models/qwen3.5-plus/limit");
        limits.put("context", 4096).put("input", 4096).put("output", 512);
        json.writeValue(root.resolve("models-dev.json").toFile(), cache);
        var store = open(2);
        String c = conversation(store);
        String first = store.receive(c, "1", "original user requirement", 9).runId();
        store.next(first);
        store.assistant(first, new AgentProtocol.Reply("complete", List.of(), 1L));
        store.selectModel(c, qwen);
        String longText = "完整要求abc123 ".repeat(1600);
        String second = store.receive(c, "2", longText, 9).runId();
        var frozen = store.runModel(second);
        assertEquals(1000000, store.runModel(first).budget().contextWindowTokens());
        assertEquals(4096, frozen.budget().contextWindowTokens());
        var binding = models().bind(frozen);
        assertTrue(new ConversationContext(store, binding.model(), binding.counter(), frozen.budget())
                .build(store.next(second)).isEmpty());
        assertEquals(RunState.PAUSED, store.run(second).state());
        assertTrue(store.timeline(c).stream().anyMatch(row -> longText.equals(row.get("text"))));
    }

    @Test
    void endpointPresetsSelectMatchingRegionalCatalogsAndCredentials() {
        migrate("8");
        var models = models();
        var family = ProviderFamily.ZAI;
        assertEquals("https://api.z.ai/api/coding/paas/v4", family.endpoint("global-coding", ""));
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", family.endpoint("china-coding", ""));
        assertEquals("https://open.bigmodel.cn/api/paas/v4", family.endpoint("china", ""));
        models.save("zai", "china-coding", "", "domestic-test-key");
        var bound = models.freeze(new Choice("zai", "glm-5.3", "default"));
        assertEquals("china-coding", bound.preset());
        assertThrows(WorkbenchRejection.class, () -> models.freeze(new Choice("zai", "glm-5", "default")));
        assertEquals("https://llm-123.us-east-1.maas.aliyuncs.com/compatible-mode/v1",
                ProviderFamily.QWEN.endpoint("virginia-workspace", "llm-123"));
        models.save("qwen", "virginia-workspace", "llm-123", "qwen-workspace-key");
        var q = models.freeze(qwen);
        var credentials = new ProviderCredentials(new JdbcTemplate(database()), env::get);
        assertThrows(AgentException.class, () -> credentials.resolve(new WorkbenchModels.Frozen(claude,
                "anthropic", "global", "", q.credentialRef(), q.budget(), q.capabilityVersion())));
        assertFalse(ProviderFamily.KIMI.efforts("kimi-k3.1-pro", true).contains("off"));
    }

    @Test
    void versionSevenRunAndReasoningContinueWithTheHistoricalDeepSeekBinding() {
        migrate("7");
        var old = new SqliteWorkbench(database(), json);
        String c = conversation(old);
        var jdbc = new JdbcTemplate(database());
        String p = jdbc.queryForObject("SELECT project_id FROM conversation WHERE id=?", String.class, c);
        jdbc.update("INSERT INTO run(id,conversation_id,project_id,state,started_at,allowance) VALUES('legacy',?,?,'PAUSED','1',8)", c, p);
        jdbc.update("INSERT INTO record(conversation_id,run_id,role,text,calls,reasoning,recorded_at)"
                + " VALUES(?,'legacy','assistant','visible','[]','old-private','1')", c);
        migrate("8");
        var upgraded = open(2);
        var frozen = upgraded.runModel("legacy");
        assertEquals("pre-V8", frozen.capabilityVersion());
        assertEquals("legacy", upgraded.start(c, 8));
        var legacy = upgraded.next("legacy").context().records().getFirst().message();
        assertTrue(models().bind(frozen).model().canReplayWithTools(legacy));
        assertEquals("old-private", legacy.reasoningContent());
        assertFalse(upgraded.timeline(c).toString().contains("old-private"));
    }
}
