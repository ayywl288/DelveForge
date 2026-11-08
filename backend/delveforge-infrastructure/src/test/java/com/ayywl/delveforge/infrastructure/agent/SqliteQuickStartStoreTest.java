package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.quickstart.QuickStart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteQuickStartStoreTest {
    @TempDir
    Path temporary;

    @Test
    void onlyCompleteDeliveredPlansGenerateAndArtifactsSurviveRestartWithStaleness() throws Exception {
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        String revision = "a".repeat(40);
        database.setup(revision, "flow");
        var store = new SqliteQuickStartStore(database.source);
        assertTrue(store.finalDelivery("plan").isEmpty());
        database.sql("UPDATE evolution_plan SET status='COMPLETED' WHERE id='plan'");
        database.sql("UPDATE working_copy SET status='CLOSED'");
        database.sql("UPDATE evolution_step SET status='SUCCEEDED',delivery_revision='" + revision + "'");
        assertTrue(store.finalDelivery("plan").isEmpty(), "Step status alone cannot manufacture a Delivery");
        database.sql("INSERT INTO candidate_delivery VALUES('candidate','run','plan','step','copy','" + revision
                + "','" + "b".repeat(40) + "','managed','original','COMPLETED','" + revision
                + "',NULL,'2026-11-08T00:00:00Z',0)");
        assertEquals("candidate", store.finalDelivery("plan").orElseThrow().candidateId());
        store.save(new QuickStart("plan", "candidate", revision, "AVAILABLE", "## 启动\nread README", null,
                Instant.parse("2026-11-08T00:01:00Z"), false));
        assertEquals("AVAILABLE", new SqliteQuickStartStore(database.source).find("plan").orElseThrow().status());
        database.sql("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('missing','plan',1,'goal','scope','PENDING_CONFIRMATION')");
        assertTrue(store.finalDelivery("plan").isEmpty());
        database.sql("DELETE FROM evolution_step WHERE id='missing'");
        database.sql("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,"
                + "current_summary,target_problem,target_product,target_differentiation,status,flow_id)"
                + " VALUES('new-plan','direction','asset','profile','current','problem','target','difference','ACTIVE','flow')");
        assertFalse(store.find("new-plan").orElseThrow().stale());
        database.sql("INSERT INTO candidate_delivery VALUES('new-candidate','new-run','new-plan','new-step','new-copy','"
                + revision + "','" + "c".repeat(40) + "','managed-new','original','COMPLETED','" + "d".repeat(40)
                + "',NULL,'2026-11-08T00:02:00Z',0)");
        assertTrue(store.find("plan").orElseThrow().stale());
        assertTrue(store.find("new-plan").orElseThrow().stale());
        store.save(new QuickStart("plan", "candidate", revision, "GENERATING", "", null, Instant.now(), false));
        store.interruptGenerating();
        assertEquals("GENERATION_INTERRUPTED", store.find("plan").orElseThrow().reason());
        database.sql("UPDATE software_asset SET read_permission=0");
        assertTrue(store.finalDelivery("plan").isEmpty());
    }
}
