package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.AuthorizedStepRollback;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyBasisChangedException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyRollbackException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.ExecutionResult;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.evolution.VerificationResult;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.evolution.WorkingCopyStatus;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.commit;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.headRevision;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.runGit;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实内容篡改夹具；刻意建立会漏报的稳定 Git stat 缓存，不用 mocked diff 证明恢复。 */
class GitRollbackContentIntegrationTest {
    private final GitTestRepositories repositories = new GitTestRepositories();
    private final GitWorkspaceAdapter adapter = new GitWorkspaceAdapter(repositories.directory("managed"));
    private Path source;
    private Path copy;
    private String baseline;
    private String candidate;

    @AfterEach
    void cleanup() throws Exception {
        repositories.deleteAll();
    }

    private AuthorizedStepRollback fixture() throws Exception {
        return fixture("*.txt -text\n");
    }

    private AuthorizedStepRollback fixture(String attributes) throws Exception {
        return fixture(attributes, false);
    }

    private AuthorizedStepRollback fixture(String attributes, boolean removeCandidateAttributes) throws Exception {
        return fixture(attributes, removeCandidateAttributes, null);
    }

    private AuthorizedStepRollback fixture(String attributes, boolean removeCandidateAttributes, String driver)
            throws Exception {
        Map<String, String> files = new LinkedHashMap<>(Map.of(".gitattributes", attributes,
                "changed.txt", "before\n", "unchanged.txt", "untouched\n", "data.bin", "binary\0content"));
        if (driver != null) {
            files.put("rules.txt", "*.txt filter=" + driver + "\n");
        }
        source = repositories.createCommitted("source", files);
        baseline = headRevision(source);
        copy = Path.of(adapter.provision(new WorkspaceRef(source.toString()), baseline, "copy").workspace().value());
        Files.writeString(copy.resolve("changed.txt"), "after \n");
        if (removeCandidateAttributes) {
            Files.writeString(copy.resolve(".gitattributes"), "*.txt -text\n");
            // baseline 的 autocrlf checkout 不再适用于新 -text；Candidate 的物理字节必须匹配其真实 blob。
            Files.writeString(copy.resolve("unchanged.txt"), "untouched\n");
            runGit(copy, "add", ".gitattributes", "unchanged.txt");
        }
        if (driver != null) {
            Files.writeString(copy.resolve("rules.txt"), "# inert Candidate rules\n");
            runGit(copy, "add", "rules.txt");
        }
        runGit(copy, "add", "changed.txt");
        commit(copy, "Candidate");
        candidate = headRevision(copy);
        EvolutionPlanId planId = new EvolutionPlanId("plan");
        EvolutionStepId stepId = new EvolutionStepId("step");
        SoftwareAssetId assetId = new SoftwareAssetId("asset");
        ExecutionResult execution = new ExecutionResult(candidate, List.of("change"), List.of("changed.txt"),
                List.of(), List.of(), Instant.now());
        VerificationResult verification = new VerificationResult(candidate, List.of(new VerificationResult.Check(
                "DIFF_CHECK", "", List.of(1), VerificationResult.Outcome.FAILED, "Whitespace failure")),
                false, List.of("Whitespace failure"), "Failed", Instant.now());
        EvolutionStep step = EvolutionStep.reconstitute(stepId, planId, "goal", "scope", List.of("change"),
                List.of(VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES)),
                EvolutionStepStatus.VERIFICATION_FAILED, baseline, execution, verification);
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "changed.txt", "fact", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        EvolutionPlan plan = EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), assetId,
                new RepositoryProfileId("profile"), new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"), List.of(step),
                List.of(), List.of(evidence), new WorkingCopyId("copy"), EvolutionPlanStatus.ACTIVE);
        WorkingCopy workingCopy = WorkingCopy.reconstitute(new WorkingCopyId("copy"), assetId, baseline, copy.toString(),
                candidate, baseline, WorkingCopyStatus.EVOLVING);
        String authorization = UUID.randomUUID().toString();
        plan.authorizeStepRollback(stepId, workingCopy, new StepExecutionPolicy(), authorization, candidate, baseline, Instant.now());
        return AuthorizedStepRollback.from(plan, stepId, workingCopy, new WorkspaceRef(source.toString()), authorization);
    }

    private void hideSameLengthChange() throws Exception {
        runGit(copy, "config", "core.trustctime", "false");
        runGit(copy, "config", "core.checkstat", "minimal");
        Path unchanged = copy.resolve("unchanged.txt");
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 10000);
        Files.setLastModifiedTime(unchanged, old);
        runGit(copy, "update-index", "--refresh");
        Files.writeString(unchanged, "tampered!\n");
        Files.setLastModifiedTime(unchanged, old);
        assertEquals("", runGit(copy, "status", "--porcelain"));
        assertEquals("", runGit(copy, "diff", "--no-ext-diff"));
        assertEquals("tampered!\n", Files.readString(unchanged));
    }

    @Test
    void trustedStateRejectsUnchangedTrackedFileHiddenByStableStatCache() throws Exception {
        fixture();
        hideSameLengthChange();
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertEquals(candidate, headRevision(copy));
        assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    @Test
    void rollbackCannotConfirmBaselineWithHiddenUnchangedFileTampering() throws Exception {
        AuthorizedStepRollback authorized = fixture();
        hideSameLengthChange();
        assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized));
        assertEquals(candidate, headRevision(copy));
        assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    @Test
    void postCheckoutHiddenTamperingCannotReturnRestoredBaseline() throws Exception {
        AuthorizedStepRollback authorized = fixture();
        GitWorkspaceAdapter injecting = new GitWorkspaceAdapter(repositories.directory("managed")) {
            @Override
            public void requireTrustedState(WorkspaceRef working, WorkspaceRef original, String expected) {
                if (expected.equals(baseline)) {
                    try {
                        hideSameLengthChange();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
                super.requireTrustedState(working, original, expected);
            }
        };
        WorkingCopyRollbackException failure = assertThrows(WorkingCopyRollbackException.class,
                () -> injecting.restoreBaseline(authorized));
        assertEquals("POSTCHECK", failure.phase());
        assertEquals(baseline, failure.observedRevision());
        assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lf", "crlf"})
    void gitAttributesAllowLegitimateCheckoutLineEndings(String eol) throws Exception {
        AuthorizedStepRollback authorized = fixture("*.txt text eol=" + eol + "\n");
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate);
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        String ending = eol.equals("crlf") ? "\r\n" : "\n";
        assertEquals("before" + ending, Files.readString(copy.resolve("changed.txt")));
        assertEquals("untouched" + ending, Files.readString(copy.resolve("unchanged.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eol=lf", "eol=crlf", "text=auto eol=lf", "text=auto eol=crlf"})
    void supportedTextAttributesProvePhysicalCrLfWithoutFilters(String attribute) throws Exception {
        AuthorizedStepRollback authorized = fixture("*.txt " + attribute + "\n");
        Files.writeString(copy.resolve("changed.txt"), "after \r\n");
        Files.writeString(copy.resolve("unchanged.txt"), "untouched\r\n");
        // 夹具模拟 Candidate 发布时的 index；生产检查器不能刷新 stat 来替代内容证明。
        runGit(copy, "add", "changed.txt", "unchanged.txt");
        assertEquals(candidate, headRevision(copy));
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate);
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        assertEquals("before\n", Files.readString(copy.resolve("changed.txt")).replace("\r\n", "\n"));
        assertEquals("untouched\n", Files.readString(copy.resolve("unchanged.txt")).replace("\r\n", "\n"));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "input"})
    void configuredAutoCrLfProvesPhysicalCrLfWithoutFilters(String autocrlf) throws Exception {
        AuthorizedStepRollback authorized = fixture("# no text attributes\n");
        runGit(copy, "config", "core.autocrlf", autocrlf);
        Files.writeString(copy.resolve("changed.txt"), "after \r\n");
        Files.writeString(copy.resolve("unchanged.txt"), "untouched\r\n");
        // 夹具模拟 Candidate 发布时的 index；生产检查器不能刷新 stat 来替代内容证明。
        runGit(copy, "add", "changed.txt", "unchanged.txt");
        assertEquals(candidate, headRevision(copy));
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate);
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        assertEquals("before\n", Files.readString(copy.resolve("changed.txt")).replace("\r\n", "\n"));
        assertEquals("untouched\n", Files.readString(copy.resolve("unchanged.txt")).replace("\r\n", "\n"));
    }

    @Test
    void customCleanFilterCannotHideContentOrRunDuringProof() throws Exception {
        fixture("*.txt filter=fixture\n");
        runGit(copy, "config", "filter.fixture.clean", "echo FILTER-INVOKED > .git/filter-ran");
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ident", "working-tree-encoding=UTF-16"})
    void unprovenContentConversionsFailClosedWithoutRewritingFiles(String attribute) throws Exception {
        fixture();
        Files.createDirectories(copy.resolve(".git/info"));
        Files.writeString(copy.resolve(".git/info/attributes"), "*.txt " + attribute + "\n");
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertEquals("untouched\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    private AuthorizedStepRollback sentinelFilter(String driver) throws Exception {
        AuthorizedStepRollback authorized = fixture("unchanged.txt filter=" + driver + "\n*.txt -text\n");
        hideSameLengthChange();
        runGit(copy, "config", "filter." + driver + ".clean",
                "printf 'untouched\\n'; printf hit >> .git/filter-ran");
        return authorized;
    }

    @ParameterizedTest
    @ValueSource(strings = {"unset", "unspecified"})
    void sentinelNamedFilterCannotForgeTrustedContentOrRunBeforeAuthorization(String driver) throws Exception {
        sentinelFilter(driver);
        assertAll(
                () -> assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                        new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate)),
                () -> assertFalse(Files.exists(copy.resolve(".git/filter-ran"))),
                () -> assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unset", "unspecified"})
    void sentinelNamedFilterCannotForgeRollbackSuccess(String driver) throws Exception {
        AuthorizedStepRollback authorized = sentinelFilter(driver);
        assertAll(
                () -> assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized)),
                () -> assertFalse(Files.exists(copy.resolve(".git/filter-ran"))),
                () -> assertEquals(candidate, headRevision(copy)),
                () -> assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unset", "unspecified"})
    void baselineOnlyFilterCannotRunDuringCheckoutBeforeFailedPostcheck(String driver) throws Exception {
        AuthorizedStepRollback authorized = fixture("*.txt filter=" + driver + "\n", true);
        runGit(copy, "config", "filter." + driver + ".smudge",
                "cat; printf hit >> .git/filter-ran");
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate);
        WorkingCopyRollbackException failure = assertThrows(WorkingCopyRollbackException.class,
                () -> adapter.restoreBaseline(authorized));
        assertEquals("BASELINE_ATTRIBUTES", failure.phase());
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
        assertEquals(candidate, headRevision(copy));
        assertEquals("after \n", Files.readString(copy.resolve("changed.txt")));
    }

    @ParameterizedTest
    @CsvSource({"unset,clean", "unspecified,clean", "unset,process", "unspecified,process"})
    void mutableExternalAttributeSourceBlocksBeforeAuthorizationAndUserRestore(String driver, String kind)
            throws Exception {
        AuthorizedStepRollback authorized = fixture("*.txt -text\n", false, driver);
        runGit(copy, "config", "core.attributesFile", "rules.txt");
        runGit(copy, "config", "filter." + driver + "." + kind, kind.equals("clean")
                ? "printf hit >> .git/filter-ran; cat" : "printf hit >> .git/filter-ran; exit 1");
        // 两个固定 revision 的 --attr-source 均仍读取当前 inert 外部来源，不能证明恢复后的来源。
        assertEquals("", runGit(copy, "--attr-source=" + baseline, "check-attr", "filter", "--", "unchanged.txt")
                .replace("unchanged.txt: filter: unspecified", "").trim());
        assertAll(
                () -> assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                        new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate)),
                () -> assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized)),
                () -> assertFalse(Files.exists(copy.resolve(".git/filter-ran"))),
                () -> assertEquals(candidate, headRevision(copy)),
                () -> assertEquals("# inert Candidate rules\n", Files.readString(copy.resolve("rules.txt"))),
                () -> assertEquals("after \n", Files.readString(copy.resolve("changed.txt"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative", "absolute", "include", "info", "space"})
    void externalAttributeAliasesAreRejectedWithoutChangingConfiguration(String location) throws Exception {
        AuthorizedStepRollback authorized = fixture();
        if (location.equals("info")) {
            Files.createDirectories(copy.resolve(".git/info"));
            Files.writeString(copy.resolve(".git/info/attributes"), "# currently inert\n");
        } else if (location.equals("include")) {
            Files.writeString(copy.resolve(".git/attribute-config"), "[core]\n attributesFile = changed.txt\n");
            runGit(copy, "config", "include.path", "attribute-config");
        } else {
            runGit(copy, "config", "core.attributesFile",
                    location.equals("absolute") ? copy.resolve("changed.txt").toString()
                            : location.equals("space") ? " " : "changed.txt");
        }
        String configuration = Files.readString(copy.resolve(".git/config"));
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        WorkingCopyRollbackException failure = assertThrows(WorkingCopyRollbackException.class,
                () -> adapter.restoreBaseline(authorized));
        assertEquals("ATTRIBUTE_SOURCES", failure.phase());
        assertEquals(candidate, headRevision(copy));
        assertEquals("after \n", Files.readString(copy.resolve("changed.txt")));
        assertEquals(configuration, Files.readString(copy.resolve(".git/config")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unset", "unspecified"})
    void controlledCommandsPinExternalAttributesAfterTheirSourceChanges(String driver) throws Exception {
        fixture("*.txt -text\n", false, driver);
        runGit(copy, "config", "core.attributesFile", "rules.txt");
        Files.writeString(copy.resolve("rules.txt"), "*.txt filter=" + driver + "\n");
        runGit(copy, "config", "filter." + driver + ".clean", "printf hit >> .git/filter-ran; cat");
        assertEquals("unchanged.txt: filter: " + driver,
                runGit(copy, "check-attr", "filter", "--", "unchanged.txt").trim());
        // 直接验证共享有界命令入口的固定覆盖值；不通过危险恢复来试探来源是否安全。
        WorkspaceCommandRunner.Result result = adapter.mutationGitResult(copy, null,
                "check-attr", "--all", "--", "unchanged.txt");
        assertTrue(result.succeeded());
        assertFalse(result.output().contains("filter"));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
        assertEquals("rules.txt", runGit(copy, "config", "--get", "core.attributesFile").trim());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unused.txt filter=unset", "[attr]dormant filter=unspecified"})
    void dormantVersionedConversionRulesCannotActivateDuringRestoration(String rule) throws Exception {
        AuthorizedStepRollback authorized = fixture(rule + "\n*.txt -text\n", true);
        runGit(copy, "config", "filter.unset.clean", "printf hit >> .git/filter-ran; cat");
        runGit(copy, "config", "filter.unspecified.process", "printf hit >> .git/filter-ran; exit 1");
        WorkingCopyRollbackException failure = assertThrows(WorkingCopyRollbackException.class,
                () -> adapter.restoreBaseline(authorized));
        assertEquals("BASELINE_ATTRIBUTES", failure.phase());
        assertEquals(candidate, headRevision(copy));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
    }

    @Test
    void defaultGlobalAttributesAreExcludedFromTheActualControlledGitProcesses() throws Exception {
        fixture();
        Path configuration = repositories.directory("private-config");
        Files.createDirectories(configuration.resolve("git"));
        Files.writeString(configuration.resolve("git/attributes"), "*.txt filter=unset\n");
        runGit(copy, "config", "filter.unset.clean", "printf hit >> .git/filter-ran; cat");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), AttributeProbe.class.getName(), copy.toString(),
                source.toString(), candidate).redirectErrorStream(true);
        WorkspaceCommandRunner.isolateEnvironment(builder.environment());
        builder.environment().put("XDG_CONFIG_HOME", configuration.toString());
        builder.environment().put("GIT_ATTR_NOSYSTEM", "0");
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("TRUSTED", new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
        assertEquals(candidate, headRevision(copy));
    }

    public static class AttributeProbe {
        public static void main(String[] arguments) {
            Path copy = Path.of(arguments[0]);
            GitWorkspaceAdapter adapter = new GitWorkspaceAdapter(copy.getParent());
            adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(arguments[1]), arguments[2]);
            System.out.print("TRUSTED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"filter", "ident"})
    void ordinaryAttributePathPatternsAreNotExternalConversionDefinitions(String pattern) throws Exception {
        AuthorizedStepRollback authorized = fixture(pattern + " text eol=lf\n*.txt -text\n");
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        assertEquals("before\n", Files.readString(copy.resolve("changed.txt")));
    }

    @Test
    void ordinaryRollbackConfirmsAllBaselineFiles() throws Exception {
        AuthorizedStepRollback authorized = fixture();
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        assertEquals("before\n", Files.readString(copy.resolve("changed.txt")));
        assertEquals("untouched\n", Files.readString(copy.resolve("unchanged.txt")));
        assertEquals(baseline, headRevision(copy));
    }
}
