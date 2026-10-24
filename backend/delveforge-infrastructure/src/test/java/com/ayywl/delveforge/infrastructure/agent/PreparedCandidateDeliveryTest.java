package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.infrastructure.persistence.workingcopy.PreparationProvenanceJson;
import com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 合成且已显式授权的 Step 夹具；真实 Git/SQLite，不调用模型或伪造真实 Provider 证据。 */
class PreparedCandidateDeliveryTest {
    private final GitTestRepositories repositories = new GitTestRepositories();

    @AfterEach
    void cleanup() throws Exception {
        repositories.deleteAll();
    }

    @Test
    void durablePreparedBaselineReplaysCandidateAndDeliversWithParentPAndTreeB() throws Exception {
        Path original = repositories.createCommitted("original", Map.of("pom.xml",
                "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>",
                "Counter.java", "class Counter { int next(int n) { return n; } }\n",
                "src/main/resources/config.txt", "resource\n", "target/classes/config.txt", "resource\n"));
        String a = GitTestRepositories.headRevision(original);
        var originalBefore = GitTestRepositories.snapshot(original);
        var workspace = new GitWorkspaceAdapter(repositories.directory("managed"));
        var receipt = workspace.provision(new WorkspaceRef(original.toString()), a, "copy");
        Path copy = Path.of(receipt.workspace().value());
        String p = receipt.revision();
        assertNotEquals(a, p);
        Path result = repositories.directory("candidate");
        GitTestRepositories.runGit(original, "clone", "--no-hardlinks", copy.toString(), result.toString());
        Files.writeString(result.resolve("Counter.java"), "class Counter { int next(int n) { return n + 1; } }\n");
        GitTestRepositories.runGit(result, "add", ".");
        String diff = GitTestRepositories.runGit(result, "diff", "--cached", "--full-index", "--no-renames", p);
        String b = GitTestRepositories.runGit(result, "write-tree").strip();
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + repositories.directory("prepared.db"));
        Flyway.configure().dataSource(source).load().migrate();
        execute(source, "INSERT INTO software_asset VALUES('asset','GIT_REPOSITORY','USER_SPECIFIED',?,1,'MIT','ALLOWED',0)",
                original.toString());
        execute(source, "INSERT INTO product_direction(id,user_profile_id,user_profile_revision,title,problem,target_product,"
                + "user_fit,differentiation,technical_value,estimated_complexity,status,flow_id) VALUES"
                + "('direction','user',1,'title','problem','target','fit','diff','value','small','SELECTED','standalone')");
        execute(source, "INSERT INTO working_copy(id,source_asset_id,source_revision,location,current_revision,"
                + "last_verified_revision,status,prepared_revision,preparation_json) VALUES('copy','asset',?,?,?,?,'READY',?,?)",
                a, copy.toString(), p, p, p, PreparationProvenanceJson.write(receipt.provenance()));
        execute(source, "INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,"
                + "working_copy_id,current_summary,target_problem,target_product,target_differentiation,status,flow_id)"
                + " VALUES('plan','direction','asset','profile','copy','current','problem','target','difference','ACTIVE','standalone')");
        execute(source, "INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('step','plan',0,'goal','scope','READY')");
        var agents = new SqliteAgentStore(source);
        AgentProtocol.Run run = new AgentProtocol.Run(UUID.randomUUID().toString(), "plan", "step", "copy", p,
                AgentRunState.CREATED, Instant.now(), null, null, null, false, false, 0, 0, null);
        agents.reserve(run);
        agents.advance(run.id(), AgentRunState.CREATED, AgentRunState.PREPARING, null);
        agents.advance(run.id(), AgentRunState.PREPARING, AgentRunState.RUNNING, null);
        agents.advance(run.id(), AgentRunState.RUNNING, AgentRunState.FINALIZING, null);
        Candidate candidate = new Candidate(UUID.randomUUID().toString(), run.id(), p, b, diff, Candidate.digest(diff),
                List.of("Counter.java"), "{\"status\":\"NOT_TESTED\"}", Instant.now());
        agents.freeze(candidate);
        agents.release(run.id(), false);
        // 重建所有服务，证明准备来源不依赖进程内状态。
        var eligibility = CodingAgentServiceTest.eligibility(p, copy, new GitWorkspaceAdapter(copy.getParent()),
                original, receipt.provenance());
        var deliveries = new SqliteDeliveryStore(source, eligibility);
        Delivery delivery;
        try (var service = new CandidateDeliveryService(new SqliteAgentStore(source), deliveries,
                new GitWorkspaceAdapter(copy.getParent()), eligibility)) {
            delivery = service.deliver(candidate.id());
            assertEquals(Delivery.State.COMPLETED, delivery.state());
        }
        String c = delivery.revision();
        assertEquals(c + " " + p, GitTestRepositories.runGit(copy, "rev-list", "--parents", "-n", "1", c).strip());
        assertEquals(b, GitTestRepositories.runGit(copy, "rev-parse", c + "^{tree}").strip());
        assertEquals(a, scalar(source, "SELECT source_revision FROM working_copy"));
        assertEquals(p, scalar(source, "SELECT prepared_revision FROM working_copy"));
        assertEquals(c, scalar(source, "SELECT current_revision FROM working_copy"));
        assertEquals(c, scalar(source, "SELECT last_verified_revision FROM working_copy"));
        assertEquals("SUCCEEDED", scalar(source, "SELECT status FROM evolution_step"));
        assertThrows(SQLException.class, () -> execute(source,
                "UPDATE working_copy SET prepared_revision=? WHERE id='copy'", a));
        assertEquals(receipt.provenance(), PreparationProvenanceJson.read(a, p,
                scalar(source, "SELECT preparation_json FROM working_copy")));
        assertEquals(originalBefore, GitTestRepositories.snapshot(original));
        assertFalse(Files.exists(copy.resolve("target/classes/config.txt")));
        try (var service = new CandidateDeliveryService(new SqliteAgentStore(source), new SqliteDeliveryStore(source, eligibility),
                new GitWorkspaceAdapter(copy.getParent()), eligibility)) {
            assertEquals(delivery, service.deliver(candidate.id()));
        }
    }

    static void execute(SQLiteDataSource source, String sql, Object... values) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.execute();
        }
    }

    private static String scalar(SQLiteDataSource source, String sql) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
