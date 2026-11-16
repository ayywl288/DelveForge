package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSchedulingTest {
    @TempDir Path root;

    private SQLiteDataSource source() {
        var config = new SQLiteConfig();
        config.setBusyTimeout(10000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        var source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + root.resolve("control.db"));
        return source;
    }

    private String project(Workbench store) {
        String project = store.beginProject("项目", null);
        store.projectResult(project, root.resolve(project).toString(), null);
        return project;
    }

    @Test
    void independentConnectionsShareTheQuotaAndClaimEligibleWorkInOrder() throws Exception {
        var ds = source();
        Flyway.configure().dataSource(ds).locations("classpath:db/workbench").load().migrate();
        Workbench left = new SqliteWorkbench(ds, new ObjectMapper(), 2);
        Workbench right = new SqliteWorkbench(source(), new ObjectMapper(), 2);
        var conversations = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            conversations.add(left.conversation(project(left), "请求"));
        }
        var starts = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = new ArrayList<java.util.concurrent.Future<Workbench.Ack>>();
            for (int i = 0; i < conversations.size(); i++) {
                String conversation = conversations.get(i);
                Workbench connection = i % 2 == 0 ? left : right;
                submissions.add(workers.submit(() -> {
                    starts.await();
                    return connection.receive(conversation, "request", "执行", 4);
                }));
            }
            starts.countDown();
            for (var submitted : submissions) {
                assertNotNull(submitted.get().runId());
            }
        }
        assertEquals(2, left.owners().size());
        var jdbc = new JdbcTemplate(ds);
        var fifo = jdbc.queryForList("SELECT id FROM run WHERE state='QUEUED' ORDER BY queue_order", String.class);
        assertEquals(10, fifo.size());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE run SET state='RUNNING' WHERE id=?", fifo.getFirst()));
        for (String next : fifo) {
            left.state(left.owners().getFirst().id(), RunState.COMPLETED, "完成");
            assertEquals(next, right.claimNext().orElseThrow());
            assertEquals(2, left.owners().size());
        }
    }

    @Test
    void blockedOwnerKeepsOneSlotAndOnlyItsProjectWhileCancelledInputRemainsVisible() {
        var ds = source();
        Flyway.configure().dataSource(ds).locations("classpath:db/workbench").load().migrate();
        var store = new SqliteWorkbench(ds, new ObjectMapper());
        String a = project(store);
        String first = store.conversation(a, "A1");
        String second = store.conversation(a, "A2");
        String other = store.conversation(project(store), "B");
        String waiting = store.conversation(project(store), "C");
        String blocked = store.receive(first, "a", "运行", 4).runId();
        store.state(blocked, RunState.BLOCKED, "进程尚未核实");
        String same = store.receive(second, "a2", "待取消的要求", 7).runId();
        String b = store.receive(other, "b", "独立执行", 3).runId();
        String c = store.receive(waiting, "c", "下一个", 8).runId();
        assertEquals(RunState.RUNNING, store.run(b).state());
        assertEquals(RunState.QUEUED, store.run(c).state());
        assertEquals(RunState.QUEUED, store.run(same).state());
        assertEquals(same, store.receive(second, "a2", "待取消的要求", 7).runId());
        store.cancelQueued(same);
        assertEquals(RunState.STOPPED, store.run(same).state());
        assertEquals("待取消的要求",store.timeline(second).getFirst().get("text"));
        store.state(b, RunState.COMPLETED, "完成");
        assertEquals(c, store.claimNext().orElseThrow());
        assertThrows(WorkbenchRejection.class, () -> store.cancelQueued(c));
        assertTrue(store.claimNext().isEmpty());
        assertEquals(List.of(blocked, c).stream().sorted().toList(),
                store.owners().stream().map(Workbench.Run::id).sorted().toList());
    }

    @Test
    void migrationPreservesUncertainLegacyOwnershipAndReopensArchivedInputs() {
        var ds = source();
        Flyway.configure().dataSource(ds).locations("classpath:db/workbench").target("6").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO project(id,name,state,created_at) VALUES('p','p','READY','1'),('q','q','READY','1')");
        jdbc.update("INSERT INTO conversation(id,project_id,title,created_at) VALUES('a','p','a','1'),('b','q','b','1')");
        jdbc.update("INSERT INTO run(id,conversation_id,project_id,state,started_at,allowance) VALUES('old','a','p','BLOCKED','1',9)");
        jdbc.update("INSERT INTO input(conversation_id,request_id,text,accepted_at,request_limit) VALUES('b','key','原始请求','1',12)");
        Flyway.configure().dataSource(ds).locations("classpath:db/workbench").load().migrate();
        var store = new SqliteWorkbench(ds,new ObjectMapper());
        store.restoreQueuedInputs(96);
        store.restoreQueuedInputs(96);
        assertTrue(store.claimNext().isEmpty());
        store.state("old",RunState.PAUSED,"原执行结束");
        String promoted=store.claimNext().orElseThrow();
        assertEquals("原始请求",store.next(promoted).context().records().getFirst().message().text());
        assertEquals(12,store.runs("b").getFirst().get("allowance"));
        assertEquals(1,store.runs("b").size());
    }
}
