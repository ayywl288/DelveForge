package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.ExecutionEnvironment;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection.Kind;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class SqliteWorkbench implements Workbench {
    private static final String OWNS = "state IN ('RUNNING','STOPPING','RECONCILING','BLOCKED')";
    private static final TypeReference<List<AgentProtocol.ToolCall>> CALLS =
            new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Object writes = new Object();

    public SqliteWorkbench(DataSource source, ObjectMapper json) {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.json = json;
    }

    // 一个控制实例拥有环境；所有输入、执行准入与结束提交在短 SQLite 事务内排序。
    private <T> T tx(Supplier<T> operation) {
        synchronized (writes) {
            return transactions.execute(status -> operation.get());
        }
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("协议序列化失败", e);
        }
    }

    private List<AgentProtocol.ToolCall> decode(String calls) {
        try {
            return json.readValue(calls, CALLS);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("持久化工具调用不可读", e);
        }
    }

    @Override
    public String beginProject(String name, String source) {
        String project = id();
        tx(
                () ->
                        jdbc.update(
                                "INSERT INTO project(id,name,source,state,created_at)"
                                        + " VALUES(?,?,?,'IMPORTING',?)",
                                project,
                                name,
                                source,
                                now()));
        return project;
    }

    @Override
    public void projectResult(String id, String workcopy, String error) {
        tx(
                () ->
                        jdbc.update(
                                "UPDATE project SET state=?,workcopy=?,detail=? WHERE id=? AND"
                                        + " state='IMPORTING'",
                                error == null ? "READY" : "FAILED",
                                workcopy,
                                error,
                                id));
    }

    @Override
    public List<Map<String, Object>> projects() {
        return jdbc.queryForList(
                "SELECT id,name,workcopy,state,detail FROM project ORDER BY created_at");
    }

    @Override
    public String conversation(String project, String name) {
        return tx(
                () -> {
                    if (count("SELECT count(*) FROM project WHERE id=? AND state='READY'", project)
                            == 0) {
                        throw new WorkbenchRejection(Kind.MISSING, "项目尚未就绪或不存在");
                    }
                    String conversation = id();
                    jdbc.update(
                            "INSERT INTO conversation(id,project_id,title,created_at)"
                                    + " VALUES(?,?,?,?)",
                            conversation,
                            project,
                            name,
                            now());
                    return conversation;
                });
    }

    @Override
    public List<Map<String, Object>> conversations(String project) {
        return jdbc.queryForList(
                "SELECT id,title,project_id FROM conversation WHERE project_id=? ORDER BY"
                        + " created_at",
                project);
    }

    private static Run readRun(ResultSet rs, int row) throws SQLException {
        return new Run(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("project_id"),
                RunState.valueOf(rs.getString("state")),
                rs.getLong("observed_input"),
                rs.getBoolean("stop"));
    }

    @Override
    public Run run(String id) {
        return jdbc.query("SELECT * FROM run WHERE id=?", SqliteWorkbench::readRun, id).stream()
                .findFirst()
                .orElseThrow(() -> new WorkbenchRejection(Kind.MISSING, "Run 不存在"));
    }

    @Override
    public List<Run> owners() {
        return jdbc.query("SELECT * FROM run WHERE " + OWNS, SqliteWorkbench::readRun);
    }

    private String projectOf(String conversation) {
        return jdbc
                .query(
                        "SELECT project_id FROM conversation WHERE id=?",
                        (rs, i) -> rs.getString(1),
                        conversation)
                .stream()
                .findFirst()
                .orElseThrow(() -> new WorkbenchRejection(Kind.MISSING, "对话不存在"));
    }

    private String newRun(String conversation, String project, int allowance) {
        String run = id();
        jdbc.update(
                "INSERT INTO run(id,conversation_id,project_id,state,started_at,allowance)"
                        + " VALUES(?,?,?,'RUNNING',?,?)",
                run,
                conversation,
                project,
                now(),
                allowance);
        return run;
    }

    @Override
    public Ack receive(String conversation, String requestId, String text, int defaultLimit, Integer requestLimit) {
        return tx(
                () -> {
                    String project = projectOf(conversation);
                    int added =
                            jdbc.update(
                                    "INSERT INTO input(conversation_id,request_id,text,accepted_at,request_limit)"
                                        + " VALUES(?,?,?,?,?) ON CONFLICT(conversation_id,request_id)"
                                        + " DO NOTHING",
                                    conversation,
                                    requestId,
                                    text,
                                    now(),
                                    requestLimit);
                    var accepted =
                            jdbc.queryForMap(
                                    "SELECT seq,text,acknowledged_run,request_limit FROM input WHERE"
                                            + " conversation_id=? AND request_id=?",
                                    conversation,
                                    requestId);
                    if (!text.equals(accepted.get("text"))
                            || !java.util.Objects.equals(requestLimit, accepted.get("request_limit"))) {
                        throw new WorkbenchRejection(Kind.BUSY, "同一消息 ID 已用于不同内容");
                    }
                    long seq = ((Number) accepted.get("seq")).longValue();
                    if (added == 0) {
                        return new Ack(seq, (String) accepted.get("acknowledged_run"), false);
                    }
                    var current = owners();
                    if (!current.isEmpty()) {
                        Run owner = current.getFirst();
                        String joined =
                                owner.conversationId().equals(conversation) ? owner.id() : null;
                        jdbc.update("UPDATE input SET acknowledged_run=? WHERE seq=?", joined, seq);
                        return new Ack(seq, joined, false);
                    }
                    if (count(
                                    "SELECT count(*) FROM run WHERE conversation_id=? AND"
                                            + " state='PAUSED'",
                                    conversation)
                            > 0) {
                        return new Ack(seq, null, false);
                    }
                    String run = newRun(conversation, project, pendingLimit(conversation, defaultLimit));
                    jdbc.update("UPDATE input SET acknowledged_run=? WHERE seq=?", run, seq);
                    return new Ack(seq, run, true);
                });
    }

    @Override
    public String start(String conversation, int defaultLimit, Integer newLimit) {
        return tx(
                () -> {
                    String project = projectOf(conversation);
                    if (!owners().isEmpty()) {
                        throw new WorkbenchRejection(Kind.BUSY, "共享环境已有前台 Run");
                    }
                    var paused =
                            jdbc.queryForList(
                                    "SELECT id,requests,allowance FROM run WHERE conversation_id=? AND state='PAUSED'",
                                    conversation);
                    if (!paused.isEmpty()) {
                        var row = paused.getFirst();
                        String id = (String) row.get("id");
                        int used = ((Number) row.get("requests")).intValue();
                        int previous = ((Number) row.get("allowance")).intValue();
                        int limit = newLimit == null ? previous : newLimit;
                        if (limit < previous || limit <= used) {
                            throw new WorkbenchRejection(Kind.BUSY, "请提高本任务的请求总上限后继续。已使用 " + used + " 次");
                        }
                        jdbc.update(
                                "UPDATE run SET state='RUNNING',detail=NULL,allowance=?"
                                        + " WHERE id=?",
                                limit,
                                id);
                        return id;
                    }
                    if (count(
                                    "SELECT count(*) FROM input WHERE conversation_id=? AND"
                                            + " consumed=0",
                                    conversation)
                            == 0) {
                        throw new WorkbenchRejection(Kind.BUSY, "当前对话没有待处理消息");
                    }
                    return newRun(conversation, project, newLimit == null ? pendingLimit(conversation, defaultLimit) : newLimit);
                });
    }

    private int pendingLimit(String conversation, int defaultLimit) {
        return jdbc.queryForObject(
                "SELECT COALESCE(request_limit,?) FROM input WHERE conversation_id=? AND consumed=0 ORDER BY seq LIMIT 1",
                Integer.class, defaultLimit, conversation);
    }

    private long record(
            Run run,
            String role,
            String text,
            String calls,
            String provider,
            String name,
            String reasoning) {
        jdbc.update(
                "INSERT INTO"
                    + " record(conversation_id,run_id,role,text,calls,provider_id,tool_name,reasoning,recorded_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                run.conversationId(),
                run.id(),
                role,
                text,
                calls,
                provider,
                name,
                reasoning,
                now());
        return count("SELECT last_insert_rowid()");
    }

    private boolean corrected(Run run) {
        return count(
                        "SELECT count(*) FROM input WHERE conversation_id=? AND seq>? AND"
                                + " consumed=0",
                        run.conversationId(),
                        run.observed())
                != 0;
    }

    private boolean currentDecision(Run run) {
        return run.state() == RunState.RUNNING && !run.stop() && !corrected(run);
    }

    @Override
    public boolean currentDecision(String id) {
        return tx(() -> currentDecision(run(id)));
    }

    @Override
    public Snapshot next(String runId) {
        return tx(
                () -> {
                    Run run = run(runId);
                    if (run.state() == RunState.RUNNING && pending(runId).isEmpty()) {
                        for (var input :
                                jdbc.queryForList(
                                        "SELECT seq,text FROM input WHERE conversation_id=? AND"
                                                + " consumed=0 ORDER BY seq",
                                        run.conversationId())) {
                            record(run, "user", (String) input.get("text"), "[]", null, null, null);
                            jdbc.update(
                                    "UPDATE input SET consumed=1 WHERE seq=?", input.get("seq"));
                            jdbc.update(
                                    "UPDATE run SET observed_input=? WHERE id=?",
                                    input.get("seq"),
                                    runId);
                        }
                    }
                    var header =
                            jdbc.queryForMap(
                                    "SELECT summary,through_seq,context_version FROM conversation"
                                            + " WHERE id=?",
                                    run.conversationId());
                    long through = ((Number) header.get("through_seq")).longValue();
                    var entries =
                            jdbc.query(
                                    "SELECT * FROM record WHERE conversation_id=? AND seq>? ORDER"
                                            + " BY seq",
                                    (rs, n) ->
                                            new Entry(
                                                    rs.getLong("seq"),
                                                    new AgentProtocol.Message(
                                                            rs.getString("role"),
                                                            rs.getString("text"),
                                                            decode(rs.getString("calls")),
                                                            rs.getString("provider_id"),
                                                            rs.getString("tool_name"),
                                                            rs.getString("reasoning"))),
                                    run.conversationId(),
                                    through);
                    return new Snapshot(
                            run(runId),
                            new Context(
                                    (String) header.get("summary"),
                                    through,
                                    ((Number) header.get("context_version")).longValue(),
                                    entries));
                });
    }

    @Override
    public boolean requestModel(String id) {
        return tx(
                () -> {
                    if (jdbc.update(
                                    "UPDATE run SET requests=requests+1 WHERE id=? AND"
                                            + " state='RUNNING' AND stop=0 AND requests<allowance",
                                    id)
                            == 1) {
                        return true;
                    }
                    jdbc.update(
                            "UPDATE run SET state='PAUSED', detail='已达到本任务的模型请求总上限' WHERE id=? AND"
                                    + " state='RUNNING' AND stop=0",
                            id);
                    return false;
                });
    }

    @Override
    public boolean assistant(String id, AgentProtocol.Reply reply) {
        return tx(
                () -> {
                    Run run = run(id);
                    String calls = encode(reply.calls());
                    boolean fresh = currentDecision(run);
                    jdbc.update(
                            "INSERT INTO model_turn(run_id,text,calls,reasoning,accepted)"
                                    + " VALUES(?,?,?,?,?)",
                            id,
                            reply.text(),
                            calls,
                            reply.reasoningContent(),
                            fresh);
                    if (reply.calls().isEmpty() && !fresh) {
                        return false;
                    }
                    long seq =
                            record(
                                    run,
                                    "assistant",
                                    reply.text(),
                                    calls,
                                    null,
                                    null,
                                    reply.reasoningContent());
                    int ordinal = 0;
                    for (var call : reply.calls()) {
                        jdbc.update(
                                "INSERT INTO"
                                    + " invocation(id,run_id,assistant_seq,position,provider_id,name,arguments,state)"
                                    + " VALUES(?,?,?,?,?,?,?,'PROPOSED')",
                                id(),
                                id,
                                seq,
                                ordinal++,
                                call.id(),
                                call.name(),
                                call.arguments());
                    }
                    if (reply.calls().isEmpty()) {
                        jdbc.update(
                                "UPDATE run SET state='COMPLETED',ended_at=? WHERE id=?",
                                now(),
                                id);
                    }
                    return true;
                });
    }

    @Override
    public List<Call> pending(String run) {
        return jdbc.query(
                "SELECT * FROM invocation WHERE run_id=? AND state!='FINISHED' ORDER BY"
                        + " assistant_seq,position",
                (rs, n) ->
                        new Call(
                                rs.getString("id"),
                                run,
                                rs.getString("provider_id"),
                                rs.getString("name"),
                                rs.getString("arguments"),
                                rs.getString("state"),
                                rs.getString("container"),
                                rs.getString("generation"),
                                rs.getString("exec_id")),
                run);
    }

    @Override
    public boolean prepare(Call call, ExecutionEnvironment.Prepared p, String hash) {
        return tx(
                () -> {
                    Run run = run(call.runId());
                    if (!currentDecision(run)) {
                        return false;
                    }
                    return jdbc.update(
                                    "UPDATE invocation SET"
                                        + " state='PREPARED',container=?,generation=?,exec_id=?,token_hash=?"
                                        + " WHERE id=? AND state='PROPOSED'",
                                    p.container(),
                                    p.generation(),
                                    p.execId(),
                                    hash,
                                    call.id())
                            == 1;
                });
    }

    @Override
    public boolean admit(Call call) {
        return tx(
                () -> {
                    Run run = run(call.runId());
                    if (!currentDecision(run)) {
                        return false;
                    }
                    return jdbc.update(
                                    "UPDATE invocation SET state='ADMITTED' WHERE id=? AND"
                                            + " state='PREPARED'",
                                    call.id())
                            == 1;
                });
    }

    @Override
    public void capture(String id, String channel, byte[] bytes) {
        tx(
                () ->
                        jdbc.update(
                                "INSERT INTO captured(invocation_id,channel,bytes) VALUES(?,?,?)",
                                id,
                                channel,
                                bytes));
    }

    @Override
    public Output excerpt(String id, int limit) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        long[] total = {0};
        jdbc.query(
                "SELECT channel,bytes FROM captured WHERE invocation_id=? ORDER BY seq",
                rs -> {
                    byte[] bytes = rs.getBytes(2);
                    total[0] += bytes.length;
                    var target = rs.getString(1).equals("stderr") ? err : out;
                    target.write(
                            bytes, 0, Math.min(bytes.length, Math.max(0, limit - target.size())));
                },
                id);
        return new Output(
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8),
                total[0],
                total[0] != out.size() + err.size());
    }

    @Override
    public void result(Call call, String text, Integer exit, boolean gap) {
        tx(
                () -> {
                    int changed =
                            jdbc.update(
                                    "UPDATE invocation SET"
                                        + " state='FINISHED',result=?,exit_code=?,gap=?,token_hash=NULL"
                                        + " WHERE id=? AND state!='FINISHED'",
                                    text,
                                    exit,
                                    gap,
                                    call.id());
                    if (changed != 0) {
                        record(
                                run(call.runId()),
                                "tool",
                                text,
                                "[]",
                                call.providerId(),
                                call.name(),
                                null);
                    }
                    return changed;
                });
    }

    @Override
    public void stop(String run) {
        tx(
                () -> {
                    run(run);
                    return jdbc.update(
                            "UPDATE run SET stop=1,ended_at=CASE WHEN state='PAUSED' THEN ? ELSE"
                                + " ended_at END,state=CASE WHEN state='PAUSED' THEN 'STOPPED' WHEN"
                                + " state='RUNNING' THEN 'STOPPING' ELSE state END WHERE id=? AND ("
                                    + OWNS
                                    + " OR state='PAUSED')",
                            now(),
                            run);
                });
    }

    @Override
    public void state(String id, RunState state, String detail) {
        tx(
                () ->
                        jdbc.update(
                                "UPDATE run SET state=?,detail=?,ended_at=? WHERE id=? AND " + OWNS,
                                state.name(),
                                detail,
                                state.ownsForeground() || state == RunState.PAUSED ? null : now(),
                                id));
    }

    @Override
    public void usage(String run, long tokens) {
        tx(() -> jdbc.update("UPDATE run SET tokens=tokens+? WHERE id=?", tokens, run));
    }

    @Override
    public void modelAttempts(String run, boolean compaction, List<Map<String, Object>> attempts, String failureDetail) {
        if (!attempts.isEmpty()) {
            tx(() -> {
                for (int i = 0; i < attempts.size(); i++) {
                    var attempt = attempts.get(i);
                    jdbc.update("INSERT INTO model_attempt(run_id,purpose,attempt,category,http_status,duration_ms,failure_detail)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                            run, compaction ? "SUMMARY" : "TURN", attempt.get("attempt"), attempt.get("category"),
                            attempt.get("httpStatus"), attempt.get("durationMs"),
                            i == attempts.size() - 1 ? failureDetail : null);
                }
                return null;
            });
        }
    }

    @Override
    public boolean summarize(String conversation, long version, long through, String summary) {
        return tx(
                () ->
                        jdbc.update(
                                        "UPDATE conversation SET summary=?,"
                                            + " through_seq=?,context_version=context_version+1"
                                            + " WHERE id=? AND context_version=? AND through_seq<?",
                                        summary,
                                        through,
                                        conversation,
                                        version,
                                        through)
                                == 1);
    }



    @Override
    public List<String> coveredUserMessages(String conversation, String run, long through) {
        return jdbc.queryForList("""
                WITH covered AS (
                    SELECT seq,run_id,text FROM record WHERE conversation_id=? AND role='user' AND seq<=?
                )
                SELECT text FROM covered
                WHERE run_id=? OR seq IN (SELECT seq FROM covered WHERE run_id<>? ORDER BY seq DESC LIMIT 3)
                ORDER BY seq
                """, String.class, conversation, through, run, run);
    }

    @Override
    public List<Map<String, Object>> timeline(String conversation) {
        return jdbc.queryForList(
                "SELECT seq,run_id,role,text,calls,provider_id,tool_name,recorded_at FROM record"
                        + " WHERE conversation_id=? UNION ALL SELECT"
                        + " seq,NULL,'pending',text,'[]',NULL,NULL,accepted_at FROM input WHERE"
                        + " conversation_id=? AND consumed=0 ORDER BY recorded_at,seq",
                conversation,
                conversation);
    }

    @Override
    public List<Map<String, Object>> runs(String conversation) {
        return jdbc.queryForList(
                "SELECT id,state,detail,started_at,ended_at,requests,tokens,allowance FROM run "
                        + "WHERE conversation_id=? ORDER BY started_at DESC",
                conversation);
    }

    @Override
    public void interruptImports() {
        tx(
                () ->
                        jdbc.update(
                                "UPDATE project SET state='FAILED',detail='上次导入被中断，请重新导入' WHERE"
                                        + " state='IMPORTING'"));
    }

    @Override
    public List<Map<String, Object>> activeTools(String conversation) {
        var rows =
                jdbc.queryForList(
                        "SELECT i.id,i.run_id,i.assistant_seq,i.provider_id,i.name,i.state "
                                + "FROM invocation i JOIN run r ON i.run_id=r.id "
                                + "WHERE r.conversation_id=? AND i.state='ADMITTED'",
                        conversation);
        for (var row : rows) {
            row.put(
                    "frames",
                    jdbc.queryForList(
                            "SELECT channel,CAST(substr(bytes,-1024) AS TEXT) text FROM (SELECT"
                                + " seq,channel,bytes FROM captured WHERE invocation_id=? ORDER BY"
                                + " seq DESC LIMIT 4) ORDER BY seq",
                            row.get("id")));
        }
        return rows;
    }

    @Override
    public Map<String, Object> lookup(String hash, String kind, String ref, int offset, int limit) {
        var scopes =
                jdbc.queryForList(
                        "SELECT r.conversation_id FROM invocation i JOIN run r ON i.run_id=r.id"
                            + " WHERE i.token_hash=? AND i.state='ADMITTED' AND r.state='RUNNING'",
                        String.class,
                        hash);
        if (scopes.isEmpty()) {
            throw new WorkbenchRejection(Kind.FORBIDDEN, "调用凭据已失效");
        }
        String conversation = scopes.getFirst();
        if ("preview".equals(kind)) {
            return Map.of("project", projectOf(conversation));
        }
        if ("output".equals(kind)) {
            return output(conversation, ref, offset, limit);
        }
        if (!"history".equals(kind)) {
            throw new WorkbenchRejection(Kind.INVALID, "未知查询");
        }
        if (!ref.isBlank()) {
            var records =
                    jdbc.queryForList(
                            "SELECT seq,role,text,calls,provider_id,tool_name FROM record WHERE"
                                    + " conversation_id=? AND seq=?",
                            conversation,
                            ref);
            if (records.isEmpty()) {
                throw new WorkbenchRejection(Kind.MISSING, "当前对话中没有这条记录");
            }
            byte[] bytes = encode(records.getFirst()).getBytes(StandardCharsets.UTF_8);
            int start = Math.min(offset, bytes.length);
            int end = (int) Math.min((long) start + limit, bytes.length);
            return Map.of(
                    "base64",
                    Base64.getEncoder()
                            .encodeToString(java.util.Arrays.copyOfRange(bytes, start, end)),
                    "next",
                    end,
                    "bytes",
                    bytes.length,
                    "eof",
                    end == bytes.length);
        }
        return Map.of(
                "entries",
                jdbc.queryForList(
                        "SELECT seq,role,substr(text,1,384) text,length(text)"
                            + " chars,provider_id,tool_name FROM record WHERE conversation_id=? AND"
                            + " seq>? ORDER BY seq LIMIT ?",
                        conversation,
                        offset,
                        Math.min(limit, 24)));
    }

    @Override
    public Map<String, Object> output(String conversation, String id, int offset, int limit) {
        if (count(
                        "SELECT count(*) FROM invocation i JOIN run r ON r.id=i.run_id WHERE i.id=?"
                                + " AND r.conversation_id=?",
                        id,
                        conversation)
                == 0) {
            throw new WorkbenchRejection(Kind.MISSING, "当前对话中没有该输出");
        }
        List<Map<String, Object>> chunks = new ArrayList<>();
        long[] cursor = {0};
        jdbc.query(
                "SELECT channel,bytes FROM captured WHERE invocation_id=? ORDER BY seq",
                rs -> {
                    byte[] bytes = rs.getBytes(2);
                    long from = Math.max(0, offset - cursor[0]),
                            to = Math.min(bytes.length, (long) offset + limit - cursor[0]);
                    if (to > from) {
                        chunks.add(
                                Map.of(
                                        "channel",
                                        rs.getString(1),
                                        "base64",
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        java.util.Arrays.copyOfRange(
                                                                bytes, (int) from, (int) to))));
                    }
                    cursor[0] += bytes.length;
                },
                id);
        return Map.of(
                "chunks",
                chunks,
                "next",
                Math.min((long) offset + limit, cursor[0]),
                "bytes",
                cursor[0],
                "gap",
                count("SELECT gap FROM invocation WHERE id=?", id) != 0);
    }
}
