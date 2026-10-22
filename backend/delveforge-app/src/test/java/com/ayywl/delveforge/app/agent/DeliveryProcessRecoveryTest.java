package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.app.DelveForgeApplication;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.evolution.preparation.ConfirmEvolutionStepUseCase;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.evolution.provisioning.PrepareEvolutionPlanUseCase;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.CandidateDeliveryPort;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningService;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.PlanningProposal;
import com.ayywl.delveforge.domain.evolution.PlanningStepProposal;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.infrastructure.agent.SqliteAgentStore;
import com.ayywl.delveforge.infrastructure.agent.SqliteDeliveryStore;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 持久化故障截面后启动全新 JVM 的真实 Spring 应用；不在同 JVM 里模拟 restart。 */
class DeliveryProcessRecoveryTest {
    @TempDir Path temporary;

    static ConfigurableApplicationContext context(Path root, boolean agent) {
        return new SpringApplicationBuilder(DelveForgeApplication.class).web(WebApplicationType.NONE).run(
                "--delveforge.persistence.database-file=" + root.resolve("application.db"),
                "--delveforge.workspace.root=" + root.resolve("copies"),
                "--delveforge.agent.sandbox.root=" + root.resolve("runs"),
                "--delveforge.agent.enabled=" + agent, "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FROZEN", "INTENT", "APPLIED", "COMMIT_READY", "PUBLISHED"})
    void newApplicationProcessReconcilesActualGitAndDatabase(String cut) throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        git(original, "init", "-q");
        Files.writeString(original.resolve("Counter.java"), "class Counter { int next(int n) { return n; } }\n");
        git(original, "add", ".");
        git(original, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "A");
        String a = git(original, "rev-parse", "HEAD");
        Path result = temporary.resolve("result");
        git(temporary, "clone", "-q", "--no-hardlinks", original.toString(), result.toString());
        Files.writeString(result.resolve("Counter.java"), "class Counter { int next(int n) { return n + 1; } }\n");
        git(result, "add", ".");
        String diff = git(result, "diff", "--cached", "--full-index", "--no-ext-diff", "--no-renames") + "\n";
        String tree = git(result, "write-tree");
        Path root = Files.createDirectory(temporary.resolve("runtime"));
        String copyLocation;
        try (var context = context(root, false)) {
            var assets = context.getBean(SoftwareAssetRepository.class);
            var assetId = new SoftwareAssetId("asset");
            var asset = SoftwareAsset.create(assetId, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                    original.toString(), true, "MIT", UsageAuthorization.ALLOWED);
            assets.save(asset);
            var profileId = new RepositoryProfileId("profile");
            var fact = new Evidence(EvidenceSourceType.REPOSITORY, "Counter.java", "counter", 1.0, true);
            var basis = new EvidenceBasis(fact, new RepositoryProfileEvidenceOrigin(profileId));
            var profile = RepositoryProfile.create(profileId, assetId, a, "Counter", List.of("Java"), List.of("counter"),
                    List.of("next"), List.of("counter"), List.of(), List.of(), List.of(fact));
            context.getBean(RepositoryProfileRepository.class).save(profile);
            var direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"), 1,
                    List.of(profileId), "Counter", "increment", "correct increment", "practice", List.of(assetId),
                    "next", "Java", "small", List.of(), new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
            direction.select();
            context.getBean(ProductDirectionRepository.class).save(direction);
            var plan = new EvolutionPlanningService(new AssetUsagePolicy(), () -> new EvolutionPlanId("plan"),
                    () -> new EvolutionStepId("step")).plan(direction, asset, profile, new PlanningProposal(
                    new CurrentState("Counter", List.of("next"), List.of("counter"), List.of()),
                    new TargetState("increment", "correct increment", "next"), List.of("counter"), List.of("increment"),
                    List.of(new PlanningStepProposal("increment", "Counter", List.of("increment"))), List.of(), List.of(basis)));
            context.getBean(EvolutionPlanRepository.class).save(plan);
            var prepared = context.getBean(PrepareEvolutionPlanUseCase.class).prepare(plan.id());
            context.getBean(ConfirmEvolutionStepUseCase.class).confirm(plan.id(), new EvolutionStepId("step"));
            var source = context.getBean(DataSource.class);
            var agents = new SqliteAgentStore(source);
            var run = new AgentProtocol.Run("run", "plan", "step", prepared.workingCopyId(), a, AgentRunState.CREATED,
                    Instant.now(), null, null, null, false, false, 0, 0, null);
            agents.reserve(run);
            agents.advance("run", AgentRunState.CREATED, AgentRunState.PREPARING, null);
            agents.advance("run", AgentRunState.PREPARING, AgentRunState.RUNNING, null);
            agents.advance("run", AgentRunState.RUNNING, AgentRunState.FINALIZING, null);
            var frozen = new Candidate("candidate", "run", a, tree, diff, Candidate.digest(diff), List.of("Counter.java"),
                    "{\"status\":\"NOT_TESTED\"}", Instant.now());
            agents.freeze(frozen);
            agents.release("run", false);
            copyLocation = new JdbcTemplate(source).queryForObject("SELECT location FROM working_copy", String.class);
            var store = new SqliteDeliveryStore(source, context.getBean(ExecutionPreparationState.class));
            if (!cut.equals("FROZEN")) {
                var delivery = store.reserve(frozen, agents.get("run"), copyLocation, original.toString());
                var git = context.getBean(CandidateDeliveryPort.class);
                if (!cut.equals("INTENT")) {
                    git.replay(delivery, frozen);
                    git.apply(delivery, frozen);
                }
                if (cut.equals("COMMIT_READY") || cut.equals("PUBLISHED")) {
                    delivery = store.advance(delivery, Delivery.State.COMMIT_READY, git.createCommit(delivery), null);
                }
                if (cut.equals("PUBLISHED")) {
                    git.publish(delivery);
                }
            }
        }
        Path output = temporary.resolve("child-" + cut + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, RecoveryProbe.class.getName(), root.toString()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("DEEPSEEK_API_KEY");
        Process child = builder.start();
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "New application process did not finish recovery");
        assertEquals(0, child.exitValue(), Files.readString(output));
        SQLiteDataSource reopened = new SQLiteDataSource();
        reopened.setUrl("jdbc:sqlite:" + root.resolve("application.db"));
        JdbcTemplate jdbc = new JdbcTemplate(reopened);
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM candidate_delivery", String.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM evolution_step", String.class));
        String c = jdbc.queryForObject("SELECT last_verified_revision FROM working_copy", String.class);
        assertEquals(c, git(Path.of(copyLocation), "rev-parse", "HEAD"));
        assertEquals(tree, git(Path.of(copyLocation), "rev-parse", "HEAD^{tree}"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_run", Integer.class));
    }

    public static class RecoveryProbe {
        public static void main(String[] arguments) throws Exception {
            try (var context = context(Path.of(arguments[0]), true)) {
                JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline) {
                    if (jdbc.queryForObject("SELECT count(*) FROM candidate_delivery WHERE state='COMPLETED'",
                            Integer.class) == 1) {
                        return;
                    }
                    Thread.sleep(100);
                }
                throw new AssertionError("Startup did not reconcile the saved frozen Candidate");
            }
        }
    }

    private static String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), result);
        return result.strip();
    }
}
