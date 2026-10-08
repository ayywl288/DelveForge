package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.evolution.execution.EvolutionExecutionException;
import com.ayywl.delveforge.application.evolution.preparation.CodingProposal;
import com.ayywl.delveforge.application.port.workspace.CandidateVerification;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationReportBudgetTest {
    private static final String PREFIX = "delveforge.workspace.verification";
    private final GitTestRepositories repositories = new GitTestRepositories();

    @TempDir
    Path run;

    @AfterEach
    void cleanRepositories() throws Exception {
        repositories.deleteAll();
    }

    @Test
    void bindingKeepsConservativeDefaultsAndAcceptsSmokeOverrides() {
        VerificationProperties defaults = properties(Map.of());
        assertEquals(Duration.ofSeconds(120), defaults.actionTimeout());
        assertEquals(Duration.ofSeconds(300), defaults.invocationTimeout());
        assertEquals(1048576, defaults.maxOutputBytes());
        assertEquals(16, defaults.maxActions());
        assertEquals(100, defaults.maxTestReports());

        VerificationProperties smoke = properties(Map.of(
                PREFIX + ".action-timeout", "900s", PREFIX + ".invocation-timeout", "1200s",
                PREFIX + ".max-output-bytes", "8388608", PREFIX + ".max-test-reports", "160"));
        assertEquals(Duration.ofSeconds(900), smoke.actionTimeout());
        assertEquals(Duration.ofSeconds(1200), smoke.invocationTimeout());
        assertEquals(8388608, smoke.maxOutputBytes());
        assertEquals(160, smoke.maxTestReports());
        assertEquals(16, smoke.maxActions());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonpositiveReportBudgetsCannotBeConstructed(int limit) {
        assertThrows(IllegalArgumentException.class, () ->
                new VerificationProperties(Duration.ofSeconds(120), Duration.ofSeconds(300), 1048576, 16, limit));
    }

    @ParameterizedTest
    @ValueSource(ints = {119, 120})
    void allFreshReportsWithinConfiguredLimitContributeEvidence(int count) throws Exception {
        writeReports(count);
        var evidence = adapter(120).testEvidence(run, 1000);
        assertEquals(count, evidence.executed());
        assertEquals(0, evidence.failures());
        assertEquals(count, evidence.passedTests().size());
        assertTrue(evidence.includes("example.Suite" + (count - 1) + "Test#value"));
    }

    @Test
    void exceedingTheConfiguredLimitFailsInsteadOfReturningPartialEvidence() throws Exception {
        writeReports(121);
        assertThrows(EvolutionExecutionException.class, () -> adapter(120).testEvidence(run, 1000));
    }

    @Test
    void defaultLimitStillRejectsThe101stFreshReport() throws Exception {
        writeReports(101);
        var adapter = new GitWorkspaceVerificationAdapter(null, null, properties(Map.of()));
        assertThrows(EvolutionExecutionException.class, () -> adapter.testEvidence(run, 1000));
    }

    @Test
    void staleReportsDoNotHideFreshEvidenceBeyondTheOldLimit() throws Exception {
        writeReports(120);
        Path stale = writeReport(120, "<failure/>");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(1000));
        var evidence = adapter(120).testEvidence(run, 1000);
        assertEquals(120, evidence.executed());
        assertEquals(0, evidence.failures());
        assertEquals(120, evidence.passedTests().size());
    }

    @Test
    void failureBeyondTheOldLimitIsNotLost() throws Exception {
        writeReports(119);
        writeReport(119, "<failure/>");
        var evidence = adapter(120).testEvidence(run, 1000);
        assertEquals(120, evidence.executed());
        assertEquals(1, evidence.failures());
        assertEquals(119, evidence.passedTests().size());
        assertFalse(evidence.includes("example.Suite119Test#value"));
    }

    @Test
    void raisingReportCountDoesNotDisableTheCumulativeByteBudget() throws Exception {
        writeReports(119);
        var adapter = new GitWorkspaceVerificationAdapter(null, null,
                new VerificationProperties(Duration.ofSeconds(120), Duration.ofSeconds(300), 1024, 16, 120));
        assertThrows(EvolutionExecutionException.class, () -> adapter.testEvidence(run, 1000));
    }

    @Test
    void realOfflineMavenSuiteWith120ReportsRequiresAnAdequateReportBudget() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.5.16</version><relativePath/></parent>
                  <groupId>example</groupId><artifactId>report-budget</artifactId><version>1</version>
                  <properties><java.version>21</java.version></properties>
                  <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                    <scope>test</scope></dependency></dependencies>
                </project>
                """);
        Path root = Path.of("../..").toAbsolutePath().normalize();
        for (String path : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            files.put(path, Files.readString(root.resolve(path)));
        }
        for (int index = 0; index < 120; index++) {
            files.put("src/test/java/Suite" + index + "Test.java", "public class Suite" + index + "Test {"
                    + " @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertEquals(2, 1+1); } }");
        }
        Path source = repositories.createCommitted("source", files);
        String revision = GitTestRepositories.headRevision(source);
        var git = new GitWorkspaceAdapter(repositories.directory("managed"));
        WorkspaceRef original = new WorkspaceRef(source.toString());
        WorkspaceRef copy = git.provision(original, revision, "copy").workspace();
        var before = GitTestRepositories.snapshot(source);
        // 此回归只验证固定 revision 的真实测试证据，不调用 mutation 或制造领域成功状态。
        var request = new CandidateVerification(copy, original, revision, revision, List.of(
                new CodingProposal.VerificationAction(CodingProposal.Action.TEST, "", List.of(1))));
        // 真实 XML 带有 JVM 属性；给足字节预算，确保负例只因报告份数失败。
        var conservative = new GitWorkspaceVerificationAdapter(git, git,
                properties(Map.of(PREFIX + ".max-output-bytes", "8388608")));
        assertThrows(EvolutionExecutionException.class, () -> conservative.verify(request));
        var configured = new GitWorkspaceVerificationAdapter(git, git,
                properties(Map.of(PREFIX + ".max-output-bytes", "8388608", PREFIX + ".max-test-reports", "120")));
        var facts = configured.verify(request);
        assertEquals(revision, facts.candidateRevision());
        assertEquals(1, facts.checks().size());
        assertTrue(facts.checks().getFirst().passed());
        assertTrue(facts.checks().getFirst().summary().contains("executed=120, failures=0"));
        assertEquals(before, GitTestRepositories.snapshot(source));
        git.requireTrustedState(copy, original, revision);
    }

    private VerificationProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate(PREFIX, Bindable.of(VerificationProperties.class));
    }

    private GitWorkspaceVerificationAdapter adapter(int maxReports) {
        return new GitWorkspaceVerificationAdapter(null, null,
                properties(Map.of(PREFIX + ".max-test-reports", Integer.toString(maxReports))));
    }

    private void writeReports(int count) throws Exception {
        for (int index = 0; index < count; index++) {
            writeReport(index, "");
        }
    }

    private Path writeReport(int index, String failure) throws Exception {
        Path report = run.resolve("module" + index % 3 + "/target/surefire-reports/TEST-Suite" + index + "Test.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "<testsuite tests=\"1\" skipped=\"0\" failures=\""
                + (failure.isEmpty() ? 0 : 1) + "\" errors=\"0\"><testcase classname=\"example.Suite" + index
                + "Test\" name=\"value\">" + failure + "</testcase></testsuite>");
        Files.setLastModifiedTime(report, FileTime.fromMillis(2000));
        return report;
    }
}
