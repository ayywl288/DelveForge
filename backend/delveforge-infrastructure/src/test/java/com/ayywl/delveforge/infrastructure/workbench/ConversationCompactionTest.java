package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentProtocol.Message;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.ExecutionEnvironment.Prepared;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchModels;
import com.ayywl.delveforge.domain.workbench.RunState;
import com.ayywl.delveforge.infrastructure.workbench.model.SpringWorkbenchModels;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.ayywl.delveforge.infrastructure.workbench.WorkbenchIntegrationTest.checkpoint;
import static com.ayywl.delveforge.infrastructure.workbench.WorkbenchIntegrationTest.finalText;
import static com.ayywl.delveforge.infrastructure.workbench.WorkbenchIntegrationTest.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationCompactionTest {
    private static final String JSON_RULE = "请把 summary --format json 的字段固定为 reader、open、overdue。";
    private static final AgentContextBudget SMALL = new AgentContextBudget(9200, 1200, .80);

    @TempDir Path temp;
    private Workbench store;
    private final SQLiteDataSource source = new SQLiteDataSource();
    private String conversation;

    @BeforeEach
    void createHistory() {
        source.setUrl("jdbc:sqlite:" + temp.resolve("history.db"));
        Flyway.configure().dataSource(source).locations("classpath:db/workbench").load().migrate();
        store = new SqliteWorkbench(source, new ObjectMapper());
        String project = store.beginProject("借阅台账", null);
        store.projectResult(project, temp.toString(), null);
        conversation = store.conversation(project, "台账验收");
    }

    private WorkbenchModels.Binding binding(String effort) {
        var choice = new WorkbenchModels.Choice("deepseek", "deepseek-flash", effort);
        var models = new SpringWorkbenchModels(source, new ObjectMapper(), temp, choice, SMALL, Duration.ofSeconds(1),
                900000, 1048576, name -> "DEEPSEEK_API_KEY".equals(name) ? "synthetic-for-context" : null);
        return models.bind(models.freeze(choice));
    }

    private void batch(String run, int ordinal, int repetitions) {
        String a = "read-" + ordinal;
        String b = "test-" + ordinal;
        var reply = new AgentProtocol.Reply("核对工作副本", List.of(tool(a), tool(b)), 1L, null, "推理 " + ordinal);
        assertTrue(store.assistant(run, reply));
        for (var call : store.pending(run)) {
            store.result(call, (call.providerId() + " 文件已读取；exit=0 outputGap=false\n").repeat(repetitions), 0, false);
        }
    }

    private static List<String> users(List<Message> messages) {
        return messages.stream().filter(m -> m.role().equals("user")).map(Message::text).toList();
    }

    private static void paired(List<Message> view) {
        Set<String> outstanding = new HashSet<>();
        for (Message message : view) {
            if ("tool".equals(message.role())) {
                assertTrue(outstanding.remove(message.callId()), "孤立回执 " + message.callId());
            } else {
                assertTrue(outstanding.isEmpty(), "批次缺少回执 " + outstanding);
                if ("assistant".equals(message.role())) {
                    assertNotNull(message.reasoningContent());
                }
                message.calls().forEach(call -> assertTrue(outstanding.add(call.id())));
            }
        }
        assertTrue(outstanding.isEmpty());
    }

    private static String history(Workbench store, String hash, long seq) throws Exception {
        var text = new ByteArrayOutputStream();
        int offset = 0;
        boolean eof;
        do {
            var page = store.lookup(hash, "history", Long.toString(seq), offset, 4096);
            text.write(Base64.getDecoder().decode((String) page.get("base64")));
            offset = ((Number) page.get("next")).intValue();
            eof = Boolean.TRUE.equals(page.get("eof"));
        } while (!eof);
        return new ObjectMapper().readTree(text.toString(StandardCharsets.UTF_8)).path("text").textValue();
    }

    private static final AgentTokenCounter CHARACTERS = (messages, tools) -> messages.stream()
            .mapToLong(m -> 10L + m.text().length()).sum();

    private Workbench.Snapshot softPressure() {
        String id = store.receive(conversation, "goal", "逐项核对测试输出，不重跑已完成的修改。", 40).runId();
        store.next(id);
        for (int i = 0; i < 8; i++) {
            batch(id, i, 4);
        }
        return store.next(id);
    }

    @Test
    void failedSummaryInEachProjectKeepsItsOwnRetryBoundary() {
        var first = softPressure();
        String other = store.beginProject("另一项目", null);
        store.projectResult(other, temp.toString(), null);
        conversation = store.conversation(other, "独立对话");
        var second = softPressure();
        var attempts = new AtomicInteger();
        var context = new ConversationContext(store, (messages, tools) -> {
            attempts.incrementAndGet();
            return finalText("无效摘要");
        }, CHARACTERS, new AgentContextBudget(4150, 250, .67));
        var originalView = context.build(first);
        context.build(second);
        assertEquals(originalView, context.build(first));
        context.build(second);
        assertEquals(2, attempts.get());
        assertEquals(0, store.next(first.run().id()).context().version());
        assertEquals(0, store.next(second.run().id()).context().version());
    }

    @ParameterizedTest
    @ValueSource(strings = {"truncated", "provider", "blank", "not-structured", "wrong-reference", "bad-id", "tool-call", "one-token"})
    void failedSoftCompressionDoesNotReplaceStateOrPayAgainForTheSamePrefix(String reason) {
        var frozen = softPressure();
        var attempts = new AtomicInteger();
        var before = new AtomicLong();
        AgentTokenCounter counter = (messages, tools) -> {
            if (!tools.isEmpty() && messages.getFirst().text().contains("模拟仅减少一枚")) {
                return before.get() - 1;
            }
            long count = CHARACTERS.countPromptTokens(messages, tools);
            if (!tools.isEmpty() && messages.size() == frozen.context().records().size() + 1) {
                before.compareAndSet(0, count);
            }
            return count;
        };
        AgentModelPort bad = (messages, tools) -> {
            attempts.incrementAndGet();
            return switch (reason) {
                case "truncated" -> throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_TRUNCATED);
                case "provider" -> throw new AgentModelFailure(AgentModelFailure.Reason.PROVIDER_CALL_FAILED);
                case "blank" -> finalText("  ");
                case "not-structured" -> finalText("去看看历史吧");
                case "wrong-reference" -> checkpoint(messages, "目标 [#99999999]");
                case "bad-id" -> checkpoint(messages, "目标 [#调用编号]");
                case "tool-call" -> new AgentProtocol.Reply("", List.of(tool("invalid")), 1L);
                case "one-token" -> checkpoint(messages, "模拟仅减少一枚");
                default -> throw new AssertionError(reason);
            };
        };
        var budget = new AgentContextBudget(4150, 250, .67);
        var context = new ConversationContext(store, bad, counter, budget);
        var view = context.build(frozen);
        assertFalse(view.isEmpty());
        assertTrue(before.get() >= budget.triggerTokens() && before.get() <= budget.usableInputTokens());
        assertEquals(frozen.context().records().stream().map(Workbench.Entry::message).toList(), view.subList(1, view.size()));
        assertEquals(view, context.build(frozen));
        batch(frozen.run().id(), 28, 1);
        assertFalse(context.build(store.next(frozen.run().id())).isEmpty());
        assertEquals(1, attempts.get());
        assertEquals(1, ((Number) store.runs(conversation).getFirst().get("requests")).intValue());
        assertEquals(0, store.next(frozen.run().id()).context().version());
    }

    @Test
    void overHardLimitCanPublishMeaningfulPartialProgressAndMakeOneAdmittedDecision() throws Exception {
        var seed = softPressure();
        store.assistant(seed.run().id(), finalText("前次核对结束"));
        var summaryCalls = new AtomicInteger();
        var turns = new CopyOnWriteArrayList<List<Message>>();
        var budget = new AgentContextBudget(3200, 300, .80);
        AgentTokenCounter counter = (messages, tools) -> {
            if (!tools.isEmpty() && messages.getFirst().text().contains("部分压缩夹具")) {
                return 2450;
            }
            return CHARACTERS.countPromptTokens(messages, tools);
        };
        AgentModelPort model = (messages, tools) -> {
            if (tools.isEmpty()) {
                summaryCalls.incrementAndGet();
                return checkpoint(messages, "部分压缩夹具：继续核对测试。");
            }
            turns.add(messages);
            return finalText("核对完成");
        };
        var context = new ConversationContext(store, model, counter, budget);
        try (var agent = new ConversationAgent(store, new ProjectWorkcopies(temp.resolve("projects")),
                new WorkbenchIntegrationTest.Guest(), model, context, new ObjectMapper(), 10)) {
            String run = agent.receive(conversation, "follow-up", JSON_RULE).runId();
            WorkbenchIntegrationTest.await(() -> store.run(run).state() == RunState.COMPLETED);
            assertEquals(1, summaryCalls.get());
            assertEquals(1, turns.size());
            long cost = counter.countPromptTokens(turns.getFirst(), ConversationContext.TOOLS);
            assertTrue(cost > budget.triggerTokens() && cost <= budget.usableInputTokens());
            assertEquals(JSON_RULE, turns.getFirst().getLast().text());
            assertTrue(turns.getFirst().getFirst().text().contains("部分压缩夹具"));
            paired(turns.getFirst());
            var row = store.runs(conversation).stream().filter(r -> run.equals(r.get("id"))).findFirst().orElseThrow();
            assertEquals(2, ((Number) row.get("requests")).intValue());
            assertEquals(2, ((Number) row.get("tokens")).intValue());
        }
    }

    @Test
    void staleSummaryCannotOverwriteWinnerOrCoverAConcurrentCorrection() {
        var frozen = softPressure();
        String run = frozen.run().id();
        long winnerThrough = frozen.context().records().getFirst().seq();
        AgentModelPort race = (messages, tools) -> {
            assertTrue(store.summarize(conversation, frozen.context().version(), winnerThrough, "已提交的工作检查点"));
            store.receive(conversation, "during", "现在只读并核对 README", 40);
            assertFalse(messages.getLast().text().contains("现在只读"));
            return checkpoint(messages, "较晚返回的旧版本");
        };
        var context = new ConversationContext(store, race, CHARACTERS, new AgentContextBudget(4150, 250, .67));
        assertTrue(context.build(frozen).isEmpty());
        var next = store.next(run);
        assertEquals(winnerThrough, next.context().through());
        assertEquals("已提交的工作检查点", next.context().summary());
        assertEquals(1, next.context().version());
        assertTrue(next.context().records().getLast().seq() > frozen.context().records().getLast().seq());
        assertFalse(store.summarize(conversation, 0, next.context().records().getLast().seq(), "过期"));
        var roomy = new ConversationContext(store, race, CHARACTERS, new AgentContextBudget(12000, 200, .9));
        var decision = roomy.build(next);
        assertEquals(List.of("逐项核对测试输出，不重跑已完成的修改。", "现在只读并核对 README"), users(decision));
        assertTrue(decision.getFirst().text().contains("已提交的工作检查点"));
        paired(decision);
    }

    @Test
    void oversizedToolGroupIsRecoverableAsAWholeAndMeaningfulCheckpointSurvives() {
        String id = store.receive(conversation, "goal", "按已商定的 CSV 契约交付。", 40).runId();
        var first = store.next(id).context().records().getLast();
        assertTrue(store.summarize(conversation, 0, first.seq(), "已完成解析；待验证 summary --format json"));
        batch(id, 99, 180);
        store.receive(conversation, "new", "保留 README，先核对缺失的输出", 40);
        var frozen = store.next(id);
        var attempts = new AtomicInteger();
        AgentModelPort mustNotSend = (m, t) -> { attempts.incrementAndGet(); throw new AssertionError("摘要输入超限"); };
        var context = new ConversationContext(store, mustNotSend, CHARACTERS, new AgentContextBudget(2400, 400, .80));
        var view = context.build(frozen);
        assertTrue(CHARACTERS.countPromptTokens(view, ConversationContext.TOOLS) <= 2000);
        assertEquals("保留 README，先核对缺失的输出", view.getLast().text());
        assertTrue(view.getFirst().text().contains("已完成解析；待验证 summary --format json"));
        for (int index : List.of(0, 2)) {
            assertTrue(view.getFirst().text().contains("[#" + frozen.context().records().get(index).seq() + "]"));
        }
        assertTrue(view.stream().noneMatch(m -> m.role().equals("tool") || !m.calls().isEmpty()));
        assertEquals(frozen.context(), store.next(id).context());
        assertEquals(0, attempts.get());
        assertEquals(view, context.build(frozen));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orphan", "missing", "duplicate", "unknown-id"})
    void malformedBatchesPauseBeforeEvenAnUnderBudgetDecision(String fault) {
        String id = store.receive(conversation, "goal", "检查工作副本", 10).runId();
        var frozen = store.next(id);
        var rows = new ArrayList<>(frozen.context().records());
        var assistant = new Message("assistant", "", List.of(tool("a"), tool("b")), null, null, "原推理");
        var a = new Message("tool", "结果 a", List.of(), "a", "Bash");
        if (!fault.equals("orphan")) {
            rows.add(new Workbench.Entry(19, assistant));
        }
        rows.add(new Workbench.Entry(20, a));
        if (fault.equals("duplicate") || fault.equals("unknown-id")) {
            rows.add(new Workbench.Entry(21, fault.equals("duplicate") ? a : new Message("tool", "结果 x", List.of(), "x", "Bash")));
        }
        var malformed = new Workbench.Snapshot(frozen.run(), new Workbench.Context("", 0, 0, rows));
        var context = new ConversationContext(store, (m, t) -> { throw new AssertionError("协议错误不能发送"); },
                CHARACTERS, SMALL);
        assertTrue(context.build(malformed).isEmpty());
        assertEquals(RunState.PAUSED, store.run(id).state());
        assertTrue(store.runs(conversation).getFirst().get("detail").toString().contains("配对"));
    }

    @Test
    void legacyBatchesWithoutReliableProvenanceKeepAllPublicEvidenceInBothThinkingModes() {
        String id = store.receive(conversation, "goal", "核对旧项目的测试结果", 10).runId();
        store.next(id);
        store.assistant(id, new AgentProtocol.Reply("", List.of(tool("old-mode")), 1L, null, null));
        store.result(store.pending(id).getFirst(), "旧模式的完整回执", 0, false);
        batch(id, 60, 1);
        var frozen = store.next(id);
        for (String effort : List.of("off", "high")) {
            var view = new ConversationContext(store, binding(effort).model(), CHARACTERS, SMALL).build(frozen);
            assertEquals("核对旧项目的测试结果", view.get(1).text());
            assertEquals(4, view.size());
            assertTrue(view.get(2).text().contains("旧模式的完整回执"));
            assertTrue(view.get(3).text().contains("read-60 文件已读取"));
            assertTrue(view.get(3).text().contains("test-60 文件已读取"));
            assertFalse(view.get(3).text().contains("推理 60"));
            assertTrue(view.subList(2, 4).stream().allMatch(m -> "user".equals(m.role()) && m.calls().isEmpty()));
        }
        assertEquals(0, ((Number) store.runs(conversation).getFirst().get("requests")).intValue());
    }

    @Test
    void overBudgetProjectedBatchesPublishACheckpointUsingTheOriginalSequences() {
        String run = store.receive(conversation, "goal", "保留文件名 Ledger.java", 30).runId();
        store.next(run);
        for (int i = 0; i < 12; i++) {
            batch(run, i, 12);
        }
        store.receive(conversation, "latest", "继续上次的验证", 30);
        var snapshot = store.next(run);
        var real = binding("high");
        var summaries = new AtomicInteger();
        AgentModelPort model = new AgentModelPort() {
            @Override
            public List<Message> projectHistory(List<Message> prefix, List<Workbench.Entry> entries) {
                return real.model().projectHistory(prefix, entries);
            }

            @Override
            public AgentProtocol.Reply call(List<Message> messages, List<AgentProtocol.ToolSpec> tools) {
                assertTrue(tools.isEmpty());
                String source = messages.getLast().text();
                assertTrue(source.contains("文件已读取") && source.contains("echo value"));
                assertFalse(source.contains("推理 0"));
                summaries.incrementAndGet();
                return checkpoint(messages, "保留文件名 Ledger.java");
            }
        };
        var context = new ConversationContext(store, model, real.counter(), SMALL);
        assertTrue(context.build(snapshot).isEmpty());
        var next = store.next(run);
        assertEquals(1, summaries.get());
        assertTrue(next.context().through() > 0);
        assertEquals(1, next.context().version());
        var view = context.build(next);
        assertEquals("继续上次的验证", view.getLast().text());
        assertTrue(users(view).contains("保留文件名 Ledger.java"));
        assertTrue(view.getFirst().text().contains("[#" + next.context().through() + "]"));
        assertEquals(1, summaries.get());
    }

    @Test
    void fittingSummaryPrefixWithAnOversizedRemainingBatchDoesNotSpendARequest() {
        String id = store.receive(conversation, "goal", "保留现有台账，先核对实际文件", 10).runId();
        store.next(id);
        batch(id, 54, 220);
        store.receive(conversation, "new", "优先 README", 10);
        var saved = store.next(id);
        var context = new ConversationContext(store, (m, t) -> { throw new AssertionError("决策尾部仍无法容纳"); },
                CHARACTERS, new AgentContextBudget(2400, 400, .80));
        var result = context.build(saved);
        assertEquals(List.of("保留现有台账，先核对实际文件", "优先 README"), users(result));
        assertTrue(CHARACTERS.countPromptTokens(result, ConversationContext.TOOLS) <= 2000);
        assertEquals(0, ((Number) store.runs(conversation).getFirst().get("requests")).intValue());
        assertEquals(saved.context(), store.next(id).context());
    }

    @Test
    void cannotTradeAnActiveCheckpointForAGenericHistoryMessageToAvoidPausing() {
        String id = store.receive(conversation, "goal", "继续实施台账", 10).runId();
        var first = store.next(id).context().records().getLast();
        String state = "已经商定的验收条件与实际证据\n".repeat(170);
        assertTrue(store.summarize(conversation, 0, first.seq(), state));
        batch(id, 99, 15);
        store.receive(conversation, "new", "继续验证", 10);
        var saved = store.next(id);
        var context = new ConversationContext(store, (m, t) -> { throw new AssertionError("摘要输入本身已超限"); },
                CHARACTERS, new AgentContextBudget(1800, 200, .8));
        assertTrue(context.build(saved).isEmpty());
        assertEquals(RunState.PAUSED, store.run(id).state());
        assertEquals(saved.context(), store.next(id).context());
        assertTrue(store.runs(conversation).getFirst().get("detail").toString().contains("已有任务检查点"));
    }

    @Test
    void anOversizedNewInstructionPausesBeforeAnyRequestAndCanResumeWithALargerWindow() {
        String instruction = "本次输入必须完整保留\n".repeat(450);
        String id = store.receive(conversation, "current", instruction, 40).runId();
        AgentModelPort never = (m, t) -> { throw new AssertionError("不应发送付费请求"); };
        var context = new ConversationContext(store, never, CHARACTERS, new AgentContextBudget(2300, 300, .8));
        var before = store.next(id);
        assertTrue(context.build(before).isEmpty());
        assertEquals(RunState.PAUSED, store.run(id).state());
        var row = store.runs(conversation).getFirst();
        assertTrue(row.get("detail").toString().contains("最新尚未回应的用户输入"));
        assertEquals(0, ((Number) row.get("requests")).intValue());
        assertEquals(before.context(), store.next(id).context());
        assertEquals(id, store.start(conversation, 40));
        var larger = new ConversationContext(store, never, CHARACTERS, new AgentContextBudget(20000, 300, .90));
        assertEquals(List.of(instruction), users(larger.build(store.next(id))));
    }

    @Test
    void aMillionTokenWindowStillCapsCoveredOriginalsAcrossSqlitePages() {
        var original = new ArrayList<String>();
        String id = store.receive(conversation, "start", "早期已归档的要求。", 10).runId();
        original.add("早期已归档的要求。");
        for (int i = 0; i < 110; i++) {
            String text = "历史更正 " + i + " alpha".repeat(62);
            store.receive(conversation, "r" + i, text, 10);
            original.add(text);
        }
        var frozen = store.next(id);
        var end = frozen.context().records().getLast().seq();
        assertTrue(store.summarize(conversation, 0, end, "旧格式检查点：保留已确认的台账操作要求。"));
        store.receive(conversation, "new", "当前输入", 10);
        var real = binding("high").counter();
        var context = new ConversationContext(store, (m, t) -> { throw new AssertionError("不应压缩"); }, real,
                new AgentContextBudget(1_000_000, 65536, .90));
        var view = context.build(store.next(id));
        var recent = users(view);
        assertEquals("当前输入", recent.getLast());
        int n = recent.size() - 1;
        assertTrue(n > 32 && n < original.size());
        assertEquals(original.subList(original.size() - n, original.size()), recent.subList(0, n));
        long raw = real.countPromptTokens(view.subList(0, n + 1), ConversationContext.TOOLS)
                - real.countPromptTokens(view.subList(0, 1), ConversationContext.TOOLS);
        assertTrue(raw > 3900 && raw <= 4096);
        System.out.printf("CONTEXT_DEFAULT rawTokens=%d rawCount=%d covered=%d%n", raw, n, original.size());
    }

    @Test
    void actualDecisionPromptKeepsC2ContractAndBoundedOriginalsAcrossIncrementalCheckpoints() throws Exception {
        String large = "原始需求：用 loans.py 实现归还并增加自动测试。另须保留档案格式 LGR-7Z。\n"
                + "样本数据 id reader due\n".repeat(165);
        String run = store.receive(conversation, "initial", large, 96).runId();
        long first = store.next(run).context().records().getFirst().seq();
        batch(run, 0, 8);
        var expectedUsers = new ArrayList<>(List.of(large));
        var requests = new ArrayList<String>();
        var summaryRequests = new ArrayList<List<Message>>();
        AgentModelPort model = (messages, tools) -> {
            assertTrue(tools.isEmpty());
            summaryRequests.add(messages);
            String material = messages.getLast().text();
            requests.add(material);
            // 有意省略 JSON 契约，只延续台账目标、证据和历史依据。
            return checkpoint(messages, "保留 loans.py 的归还与自动测试 [#" + first + "]。第 " + requests.size() + " 次合并。");
        };
        var real = binding("high").counter();
        var initialViews = new ArrayList<List<Message>>();
        AgentTokenCounter measured = (messages, tools) -> {
            if (!tools.isEmpty() && !messages.isEmpty() && messages.getFirst().text().startsWith("你是用户个人项目")) {
                initialViews.add(List.copyOf(messages));
            }
            return real.countPromptTokens(messages, tools);
        };
        var context = new ConversationContext(store, model, measured, SMALL);
        long previousThrough = 0;
        String previousSummary = "";
        for (int round = 0; round < 3; round++) {
            if (round == 1) {
                store.assistant(run, finalText("第一项交付已记录"));
                run = store.receive(conversation, "second-run", "同一项目继续按验收要求核对台账。", 96).runId();
                expectedUsers.add("同一项目继续按验收要求核对台账。");
                store.next(run);
            }
            for (int i = 0; i < 16; i++) {
                String key = round + "-" + i;
                String requirement = "修正 " + key + "：" + "以当前 CSV 文件为准，保留原始台账。".repeat(5);
                store.receive(conversation, key, requirement, 96);
                expectedUsers.add(requirement);
                store.next(run);
                batch(run, round * 100 + i + 1, 1);
            }
            String latest = round == 0 ? JSON_RULE : "补充 " + round + "：" + JSON_RULE;
            store.receive(conversation, "json-" + round, latest, 96);
            expectedUsers.add(latest);
            store.next(run);
            // 把契约放入冻结前缀，避免仅靠尚未压缩的最后一条消息通过。
            for (int i = 0; i < 4; i++) {
                batch(run, round * 100 + 70 + i, 20);
            }

            var frozen = store.next(run);
            initialViews.clear();
            var built = context.build(frozen);
            long before = real.countPromptTokens(initialViews.getFirst(), ConversationContext.TOOLS);
            assertTrue(built.isEmpty(), "round=" + round + " before=" + before + " requests=" + requests.size());
            var next = store.next(run);
            assertEquals(round + 1, next.context().version());
            assertTrue(next.context().through() > previousThrough);
            String material = requests.getLast();
            assertTrue(material.contains(previousSummary));
            if (round > 0) {
                assertFalse(material.substring(material.indexOf("本次冻结记录\n")).contains(large));
            }
            assertFalse(next.context().summary().contains("--format json"));
            assertEquals(round + 1, requests.size());
            var view = context.build(next);
            assertFalse(view.isEmpty());
            paired(view);
            assertEquals(next.context().records().stream().map(Workbench.Entry::message).toList(),
                    view.subList(view.size() - next.context().records().size(), view.size()));
            assertTrue(view.stream().anyMatch(message -> message.calls().size() == 2));
            assertTrue(next.context().records().stream().noneMatch(e -> latest.equals(e.message().text())),
                    "round=" + round + " through=" + next.context().through() + " before=" + before);
            var raw = users(view);
            assertTrue(raw.contains(latest));
            assertFalse(raw.contains(large));
            assertTrue(view.stream().noneMatch(message -> message.text().contains("LGR-7Z")));
            assertTrue(raw.size() < expectedUsers.size());
            assertEquals(expectedUsers.stream().filter(raw::contains).toList(), raw);

            long after = real.countPromptTokens(view, ConversationContext.TOOLS);
            int split = view.size() - next.context().records().size();
            long fixed = real.countPromptTokens(view.subList(0, 1), ConversationContext.TOOLS);
            long fixedAndRaw = real.countPromptTokens(view.subList(0, split), ConversationContext.TOOLS);
            long rawTokens = fixedAndRaw - fixed;
            assertTrue(rawTokens <= 1333, "已覆盖用户原文超出小窗口预算");
            assertTrue(after <= 5333, "目标检查点容量 round=" + round + " after=" + after + " before=" + before
                    + " fixed=" + fixed + " raw=" + rawTokens + " tail=" + (after - fixedAndRaw));
            assertTrue(before >= SMALL.triggerTokens());
            long summaryTokens = real.countPromptTokens(summaryRequests.getLast(), List.of());
            assertTrue(summaryTokens <= SMALL.usableInputTokens());
            System.out.printf("CONTEXT_ROUND %d before=%d after=%d rawTokens=%d rawCount=%d users=%d suffixTokens=%d "
                            + "summaryTokens=%d summaryCalls=%d through=%d%n", round + 1, before, after, rawTokens,
                    split - 1, expectedUsers.size(), after - fixedAndRaw, summaryTokens, requests.size(), next.context().through());
            previousThrough = next.context().through();
            previousSummary = next.context().summary();
        }
        // 已被摘要的超长原文可用真正的授权历史分页逐字取回。
        store.assistant(run, new AgentProtocol.Reply("", List.of(tool("lookup")), 1L, null, "读取依据"));
        var call = store.pending(run).getFirst();
        assertTrue(store.prepare(call, new Prepared("fixture", "generation", "exec"), "scope"));
        assertTrue(store.admit(call));
        assertEquals(large, history(store, "scope", first));
    }
}
