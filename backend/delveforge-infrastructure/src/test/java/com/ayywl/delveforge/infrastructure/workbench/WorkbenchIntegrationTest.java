package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.ExecutionEnvironment;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchIntegrationTest {
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();
    Workbench store;
    ProjectWorkcopies files;
    String project;
    String conversation;
    ConversationAgent agent;

    @BeforeEach
    void createDatabase() throws Exception {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("control.db"));
        Flyway.configure().dataSource(source).locations("classpath:db/workbench").load().migrate();
        store = new SqliteWorkbench(source, json);
        files = new ProjectWorkcopies(temp.resolve("projects"));
        project = store.beginProject("样例", null);
        store.projectResult(project, files.prepare(project, null), null);
        conversation = store.conversation(project, "A");
    }

    @AfterEach
    void close() {
        if (agent != null) {
            agent.close();
        }
    }

    static class Guest implements ExecutionEnvironment {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger preparations = new AtomicInteger();
        Runnable during = () -> {};
        boolean canProve = true;
        Integer exit = 0;

        @Override
        public Prepared prepare(Workbench.Run r, Workbench.Call c, String token) {
            preparations.incrementAndGet();
            return new Prepared("container", "generation", "exec-" + c.id());
        }

        @Override
        public void execute(Workbench.Call c, BiConsumer<String, byte[]> output) {
            executions.incrementAndGet();
            output.accept("stdout", "real-prefix".getBytes(StandardCharsets.UTF_8));
            during.run();
        }

        @Override
        public End reconcile(Workbench.Call c, boolean stop) {
            return new End(canProve, exit, "test proof");
        }
    }

    private void agent(AgentModelPort model, Guest guest, int allowance) {
        var context =
                new ConversationContext(
                        store, model, (h, t) -> 10, new AgentContextBudget(1000, 50, .9));
        agent = new ConversationAgent(store, files, guest, model, context, json, allowance);
    }

    static AgentProtocol.Reply finalText(String text) {
        return new AgentProtocol.Reply(text, List.of(), 1L, null, "thinking");
    }

    static AgentProtocol.ToolCall tool(String id) {
        return new AgentProtocol.ToolCall(id, "Bash", "{\"command\": \"echo value\"}");
    }

    static void await(BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < limit) {
            Thread.sleep(15);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void inputCommittedDuringModelInvalidatesFinalAndDoesNotSteerOtherConversation()
            throws Exception {
        var gate = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var turns = new AtomicInteger();
        String other = store.conversation(project, "B");
        agent(
                (messages, tools) -> {
                    if (turns.getAndIncrement() == 0) {
                        started.countDown();
                        try {
                            assertTrue(gate.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                        return finalText("旧答复");
                    }
                    assertEquals("纠正", messages.getLast().text());
                    assertFalse(messages.stream().anyMatch(m -> "别的对话".equals(m.text())));
                    return finalText("新答复");
                },
                new Guest(),
                4);
        var first = agent.receive(conversation, "first", "任务");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var correction = agent.receive(conversation, "fix", "纠正");
        var waiting = agent.receive(other, "other", "别的对话");
        assertEquals(first.runId(), correction.runId());
        assertNull(waiting.runId());
        assertFalse(waiting.newlyStarted());
        assertThrows(WorkbenchRejection.class, () -> agent.start(other));
        gate.countDown();
        await(() -> store.run(first.runId()).state() == RunState.COMPLETED);
        assertEquals(
                List.of("任务", "纠正", "新答复"),
                store.timeline(conversation).stream().map(m -> m.get("text")).toList());
        var repeated = agent.receive(conversation, "first", "任务");
        assertEquals(first.sequence(), repeated.sequence());
        assertEquals(first.runId(), repeated.runId());
        assertFalse(repeated.newlyStarted());
    }

    @Test
    void correctionBetweenToolsPreservesOriginalIdArgumentsReasoningAndPairsEveryResult()
            throws Exception {
        var guest = new Guest();
        guest.during = () -> agent.receive(conversation, "fix", "现在只需要解释");
        var turns = new AtomicInteger();
        agent(
                (history, tools) -> {
                    if (turns.getAndIncrement() == 0) {
                        return new AgentProtocol.Reply(
                                "",
                                List.of(tool("provider-A"), tool("provider-B")),
                                1L,
                                null,
                                "original reasoning");
                    }
                    var a =
                            history.stream()
                                    .filter(m -> m.role().equals("assistant"))
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals("original reasoning", a.reasoningContent());
                    assertEquals("{\"command\": \"echo value\"}", a.calls().getFirst().arguments());
                    var results = history.stream().filter(m -> m.role().equals("tool")).toList();
                    assertEquals(
                            List.of("provider-A", "provider-B"),
                            results.stream().map(AgentProtocol.Message::callId).toList());
                    assertTrue(results.get(1).text().contains("\"notStarted\":true"));
                    assertEquals("现在只需要解释", history.getLast().text());
                    return finalText("已解释");
                },
                guest,
                4);
        String id = agent.receive(conversation, "1", "任务").runId();
        await(() -> store.run(id).state() == RunState.COMPLETED);
        assertEquals(1, guest.executions.get());
        assertEquals(1, guest.preparations.get());
    }

    @Test
    void unknownHistoricalEffectReleasesOnlyAfterProofAndCapturedBytesRemainScoped()
            throws Exception {
        var ack = store.receive(conversation, "1", "开始", 4);
        var snap = store.next(ack.runId());
        store.assistant(
                ack.runId(), new AgentProtocol.Reply("", List.of(tool("one")), 1L, null, "r"));
        var call = store.pending(ack.runId()).getFirst();
        var guest = new Guest();
        guest.canProve = false;
        guest.exit = null;
        store.prepare(call, guest.prepare(snap.run(), call, "scope"), "hash");
        store.admit(call);
        store.capture(call.id(), "stderr", "abcdefgh".getBytes(StandardCharsets.UTF_8));
        assertEquals("abc", store.excerpt(call.id(), 3).stderr());
        assertTrue(store.excerpt(call.id(), 3).truncated());
        assertThrows(
                WorkbenchRejection.class,
                () -> store.output(store.conversation(project, "B"), call.id(), 0, 8));
        agent(
                (m, t) -> {
                    throw new AssertionError("恢复不会重跑模型");
                },
                guest,
                4);
        agent.startup();
        await(() -> store.run(ack.runId()).state() == RunState.BLOCKED);
        assertEquals(1, store.owners().size());
        guest.canProve = true;
        agent.recover(ack.runId());
        await(() -> store.run(ack.runId()).state() == RunState.PAUSED);
        assertTrue(store.owners().isEmpty());
        assertEquals(0, guest.executions.get());
        var output = store.output(conversation, call.id(), 2, 3);
        assertEquals(true, output.get("gap"));
        assertEquals(5L, output.get("next"));
        assertTrue(
                store.timeline(conversation)
                        .getLast()
                        .get("text")
                        .toString()
                        .contains("\"exit\":null"));
    }

    @Test
    void pauseContinueAndExplicitStopPreserveHistoryWithoutCandidate() throws Exception {
        var turns = new AtomicInteger();
        var guest = new Guest();
        agent(
                (m, t) ->
                        turns.getAndIncrement() == 0
                                ? new AgentProtocol.Reply("", List.of(tool("a")), 1L, null, "r")
                                : finalText("完成"),
                guest,
                1);
        var ack = agent.receive(conversation, "key", "task");
        await(() -> store.run(ack.runId()).state() == RunState.PAUSED);
        assertEquals(ack.runId(), agent.start(conversation));
        await(() -> store.run(ack.runId()).state() == RunState.COMPLETED);
        assertEquals(1, guest.executions.get());
        assertTrue(store.owners().isEmpty());
        var next = store.receive(conversation, "next", "stop", 1);
        store.stop(next.runId());
        agent.recover(next.runId());
        await(() -> store.run(next.runId()).state() == RunState.STOPPED);
    }

    @Test
    void compactionPublishesOnlyFrozenPrefixAndRetainsMessagesArrivingDuringSummary() {
        var ack = store.receive(conversation, "begin", "原目标", 50);
        for (int i = 0; i < 13; i++) {
            store.next(ack.runId());
            store.assistant(
                    ack.runId(),
                    new AgentProtocol.Reply("", List.of(tool("c" + i)), 1L, null, "r"));
            var pending = store.pending(ack.runId()).getFirst();
            store.result(pending, "observation ".repeat(8), 0, false);
        }
        var frozen = store.next(ack.runId());
        var originalCount = store.timeline(conversation).size();
        AgentModelPort summarize =
                (messages, tools) -> {
                    store.receive(conversation, "during", "压缩时收到的修正", 50);
                    return finalText("目标、原调用结果与剩余工作");
                };
        AgentTokenCounter counter =
                (messages, tools) ->
                        messages.stream()
                                .mapToLong(m -> m.text() == null ? 15 : m.text().length() + 12)
                                .sum();
        var ctx =
                new ConversationContext(
                        store, summarize, counter, new AgentContextBudget(2400, 100, .60));
        assertTrue(ctx.build(frozen).isEmpty());
        var latest = store.next(ack.runId());
        assertTrue(latest.context().through() > 0);
        assertEquals("压缩时收到的修正", latest.context().records().getLast().message().text());
        assertEquals(originalCount + 1, store.timeline(conversation).size());
        assertNotEquals("tool", latest.context().records().getFirst().message().role());
    }

    @Test
    void failedCompactionUsesFittingContextAndPausesWhenMandatoryInputCannotFit() {
        var ack = store.receive(conversation, "1", "必须保留".repeat(50), 10);
        var snapshot = store.next(ack.runId());
        var model =
                (AgentModelPort)
                        (m, t) -> {
                            throw new IllegalStateException("summary unavailable");
                        };
        var fits =
                new ConversationContext(
                        store, model, (m, t) -> 180, new AgentContextBudget(250, 50, .5));
        assertFalse(fits.build(snapshot).isEmpty());
        var tooSmall =
                new ConversationContext(
                        store, model, (m, t) -> 500, new AgentContextBudget(250, 50, .5));
        assertTrue(tooSmall.build(snapshot).isEmpty());
        assertEquals(RunState.PAUSED, store.run(ack.runId()).state());
        assertEquals(1, store.timeline(conversation).size());
    }

    @Test
    void lossyFallbackKeepsLastCorrectionAndOriginalPairedRecords() {
        var ack = store.receive(conversation, "first", "原用户任务", 12);
        for (int i = 0; i < 12; i++) {
            store.next(ack.runId());
            store.assistant(
                    ack.runId(),
                    new AgentProtocol.Reply("", List.of(tool("original-" + i)), 1L, null, "r"));
            store.result(store.pending(ack.runId()).getFirst(), "留存的完整输出", 0, false);
        }
        store.receive(conversation, "next", "保留刚才的修正", 12);
        var frozen = store.next(ack.runId());
        int size = store.timeline(conversation).size();
        var attempts = new AtomicInteger();
        AgentModelPort model =
                (m, t) -> {
                    attempts.incrementAndGet();
                    throw new com.ayywl.delveforge.application.agent.AgentModelFailure(
                            com.ayywl.delveforge.application.agent.AgentModelFailure.Reason
                                    .RESPONSE_TRUNCATED);
                };
        AgentTokenCounter counter =
                (m, t) -> t.isEmpty() || m.getFirst().text().contains("有损上下文") ? 40 : 600;
        var ctx =
                new ConversationContext(store, model, counter, new AgentContextBudget(300, 50, .8));
        assertTrue(ctx.build(frozen).isEmpty());
        assertEquals(2, attempts.get());
        var next = store.next(ack.runId());
        assertTrue(next.context().summary().contains("保留刚才的修正"));
        assertTrue(next.context().through() > 0);
        assertEquals(size, store.timeline(conversation).size());
        assertEquals(
                12,
                store.timeline(conversation).stream()
                        .filter(row -> "tool".equals(row.get("role")))
                        .count());
        assertFalse(ctx.build(next).isEmpty());
    }

    @Test
    void stopDuringAdmittedCommandPairsRemainingCallsAndReleasesOnlyAfterTermination()
            throws Exception {
        var running = new CountDownLatch(1);
        var terminated = new CountDownLatch(1);
        var guest =
                new Guest() {
                    @Override
                    public End reconcile(Workbench.Call call, boolean stop) {
                        if (stop) {
                            terminated.countDown();
                        }
                        return new End(terminated.getCount() == 0, 143, "已核对停止");
                    }
                };
        guest.during =
                () -> {
                    running.countDown();
                    try {
                        assertTrue(terminated.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                };
        agent(
                (m, t) ->
                        new AgentProtocol.Reply(
                                "", List.of(tool("first"), tool("second")), 1L, null, "r"),
                guest,
                4);
        var ack = agent.receive(conversation, "request", "开始任务");
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertEquals(1, store.owners().size());
        agent.stop(ack.runId());
        await(() -> store.run(ack.runId()).state() == RunState.STOPPED);
        assertEquals(1, guest.executions.get());
        assertTrue(store.owners().isEmpty());
        var paired =
                store.timeline(conversation).stream()
                        .filter(r -> "tool".equals(r.get("role")))
                        .toList();
        assertEquals(
                List.of("first", "second"),
                paired.stream().map(r -> r.get("provider_id")).toList());
        assertTrue(paired.get(1).get("text").toString().contains("notStarted"));
    }
}
