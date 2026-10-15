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
import com.ayywl.delveforge.domain.evolution.ExecutionFailureResult;
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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.commit;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.headRevision;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.runGit;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 Git 物化旧检查点；不依赖有缺陷的属性预检创建执行授权或启动 Provider。 */
class GitAttributeSafetyIntegrationTest {
    private final GitTestRepositories repositories = new GitTestRepositories();
    private final GitWorkspaceAdapter adapter = new GitWorkspaceAdapter(repositories.directory("managed"));
    private final EvolutionPlanId planId = new EvolutionPlanId("plan");
    private final EvolutionStepId stepId = new EvolutionStepId("step");
    private final SoftwareAssetId assetId = new SoftwareAssetId("asset");
    private Path source;
    private Path copy;
    private String baseline;
    private String candidate;
    private String reportPath;

    @AfterEach
    void cleanup() throws Exception {
        repositories.deleteAll();
    }

    private EvolutionPlan plan(EvolutionStep step) {
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, reportPath, "fact", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        return EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), assetId,
                new RepositoryProfileId("profile"), new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"), List.of(step),
                List.of(), List.of(evidence), new WorkingCopyId("copy"), EvolutionPlanStatus.ACTIVE);
    }

    private AuthorizedStepRollback fixture(String attributesPath, String rules, boolean automatic) throws Exception {
        return fixture(attributesPath, rules, automatic, false);
    }

    private AuthorizedStepRollback fixture(String attributesPath, String rules, boolean automatic, boolean intermediate)
            throws Exception {
        reportPath = attributesPath.contains("/") ? "src/Report.java" : "Report.java";
        Map<String, String> files = new LinkedHashMap<>(Map.of(attributesPath, rules,
                reportPath, "public class Report { int value = 0; }\n",
                "docs/attr.txt", "trusted\n"));
        String child = attributesPath.substring(0, attributesPath.lastIndexOf('/') + 1) + "src/.gitattributes";
        if (intermediate) {
            files.put(child, "\ufeff\"*\"!filter\n");
            files.put(child.replace(".gitattributes", "Payload.java"), "class Payload {}\n");
        }
        source = repositories.createCommitted("source", files);
        baseline = headRevision(source);
        copy = Path.of(adapter.provision(new WorkspaceRef(source.toString()), baseline, "copy").workspace().value());
        runGit(copy, "config", "core.autocrlf", "false");
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(copy.resolve(file.getKey()), file.getValue());
        }
        if (intermediate) {
            Files.writeString(copy.resolve(attributesPath), "# inert Candidate rules\n");
            Files.writeString(copy.resolve(child), "# inert Candidate rules\n");
            runGit(copy, "add", "--", attributesPath, child);
        }
        Files.writeString(copy.resolve(reportPath), "public class Report { int value = 1; }\n");
        runGit(copy, "add", "--", reportPath);
        if (automatic) {
            EvolutionStep step = EvolutionStep.reconstitute(stepId, planId, "goal", "scope", List.of("change"),
                    List.of(VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES)),
                    EvolutionStepStatus.READY);
            EvolutionPlan plan = plan(step);
            WorkingCopy working = WorkingCopy.reconstitute(new WorkingCopyId("copy"), assetId, baseline, copy.toString(),
                    baseline, baseline, WorkingCopyStatus.READY);
            plan.startStep(stepId, working, new StepExecutionPolicy());
            String tree = runGit(copy, "write-tree").trim();
            String unpublished = runGit(copy, "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid",
                    "commit-tree", tree, "-p", baseline, "-m", "unpublished fixture").trim();
            Path lock = copy.resolve(".git/HEAD.lock");
            WorkspaceCommandRunner.Result rejection;
            Files.writeString(lock, "OWNED PUBLICATION FAULT");
            try {
                rejection = WorkspaceCommandRunner.run(copy, List.of("git", "-C", copy.toString(),
                        "update-ref", "HEAD", unpublished, baseline), Duration.ofSeconds(10), 1024);
            } finally {
                Files.delete(lock);
            }
            assertEquals(128, rejection.exitCode());
            assertTrue(rejection.output().stripLeading().startsWith("fatal:"));
            List<ExecutionFailureResult.DiffEntry> diff = new ArrayList<>();
            for (String path : files.keySet()) {
                String original = runGit(copy, "rev-parse", baseline + ":" + path).trim();
                String index = runGit(copy, "ls-files", "--stage", "--", path).trim();
                index = index.substring(0, index.indexOf('\t'));
                String actual = runGit(copy, "hash-object", "--no-filters", "--", path).trim();
                if (!("100644 " + original + " 0").equals(index) || !original.equals(actual)) {
                    diff.add(new ExecutionFailureResult.DiffEntry(path, original, index, actual));
                }
            }
            diff.sort(Comparator.comparing(ExecutionFailureResult.DiffEntry::path));
            ExecutionFailureResult failure = new ExecutionFailureResult(UUID.randomUUID().toString(), baseline, baseline,
                    "PUBLISH", rejection.exitCode(), "CONFIRMED_GIT_REJECTION", baseline, tree, true, diff, Instant.now());
            plan.failStepExecution(stepId, working, failure);
            candidate = baseline;
            return AuthorizedStepRollback.fromExecutionFailure(plan, stepId, working, new WorkspaceRef(source.toString()));
        }
        commit(copy, "Candidate");
        candidate = headRevision(copy);
        return authorizeUserRollback();
    }

    private AuthorizedStepRollback authorizeUserRollback() {
        ExecutionResult execution = new ExecutionResult(candidate, List.of("change"), List.of(reportPath),
                List.of(), List.of(), Instant.now());
        VerificationResult verification = new VerificationResult(candidate, List.of(new VerificationResult.Check(
                "DIFF_CHECK", "", List.of(1), VerificationResult.Outcome.FAILED, "Fixture verification failure")),
                false, List.of("Fixture verification failure"), "Failed", Instant.now());
        EvolutionStep step = EvolutionStep.reconstitute(stepId, planId, "goal", "scope", List.of("change"),
                List.of(VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES)),
                EvolutionStepStatus.VERIFICATION_FAILED, baseline, execution, verification);
        EvolutionPlan plan = plan(step);
        WorkingCopy working = WorkingCopy.reconstitute(new WorkingCopyId("copy"), assetId, baseline, copy.toString(),
                candidate, baseline, WorkingCopyStatus.EVOLVING);
        String authorization = UUID.randomUUID().toString();
        plan.authorizeStepRollback(stepId, working, new StepExecutionPolicy(), authorization, candidate, baseline, Instant.now());
        return AuthorizedStepRollback.from(plan, stepId, working, new WorkspaceRef(source.toString()), authorization);
    }

    @EnabledOnOs(OS.WINDOWS)
    @ParameterizedTest
    @CsvSource({".GITATTRIBUTES,unset,clean,false", ".GITATTRIBUTES,unspecified,clean,false",
            ".GITATTRIBUTES,unset,process,false", ".GITATTRIBUTES,unspecified,process,false",
            "src/.GitAttributes,unset,clean,false", "src/.GitAttributes,unspecified,clean,false",
            "src/.GitAttributes,unset,process,false", "src/.GitAttributes,unspecified,process,false",
            ".GITATTRIBUTES,unset,clean,true", ".GITATTRIBUTES,unspecified,clean,true",
            ".GITATTRIBUTES,unset,process,true", ".GITATTRIBUTES,unspecified,process,true",
            "src/.GitAttributes,unset,clean,true", "src/.GitAttributes,unspecified,clean,true",
            "src/.GitAttributes,unset,process,true", "src/.GitAttributes,unspecified,process,true"})
    void caseVariantAttributesCannotRunFiltersBeforeUserOrAutomaticRollback(String attributes, String driver,
            String kind, boolean automatic) throws Exception {
        AuthorizedStepRollback authorized = fixture(attributes, "*.java filter=" + driver + "\n", automatic);
        // 此环境必须实际暴露大小写不敏感查找；不能把 Linux 的不执行当作 Windows 安全证据。
        assertTrue(Files.isSameFile(copy.resolve(attributes),
                copy.resolve(attributes.substring(0, attributes.lastIndexOf('/') + 1) + ".gitattributes")));
        runGit(copy, "config", "filter." + driver + "." + kind, kind.equals("clean")
                ? "cat; printf hit >> .git/filter-ran" : "printf hit >> .git/filter-ran; exit 1");
        Files.setLastModifiedTime(copy.resolve(reportPath), FileTime.fromMillis(System.currentTimeMillis() + 10000));
        String before = Files.readString(copy.resolve(reportPath));
        assertAll(
                () -> assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized)),
                () -> assertFalse(Files.exists(copy.resolve(".git/filter-ran"))),
                () -> assertEquals(candidate, headRevision(copy)),
                () -> assertEquals(before, Files.readString(copy.resolve(reportPath))),
                () -> assertEquals(baseline, headRevision(source)));
    }

    @ParameterizedTest
    @CsvSource({".GITATTRIBUTES,unset", ".GITATTRIBUTES,unspecified",
            "src/.GitAttributes,unset", "src/.GitAttributes,unspecified"})
    void baselineOnlyAliasIsRejectedBeforeCheckoutEvenWhenCandidateHasNoAttributes(String attributes, String driver)
            throws Exception {
        fixture(attributes, "*.java filter=" + driver + "\n", false);
        // 只准备本测试拥有的 tracked Candidate；避开 git rm 对歧义属性名的 stat/转换解释。
        Files.delete(copy.resolve(attributes));
        runGit(copy, "update-index", "--force-remove", "--", attributes);
        commit(copy, "Remove attributes from Candidate");
        candidate = headRevision(copy);
        AuthorizedStepRollback authorized = authorizeUserRollback();
        runGit(copy, "config", "filter." + driver + ".smudge", "cat; printf hit >> .git/filter-ran");
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate);
        String before = Files.readString(copy.resolve(reportPath));
        WorkingCopyRollbackException failure = assertThrows(WorkingCopyRollbackException.class,
                () -> adapter.restoreBaseline(authorized));
        assertEquals("BASELINE_ATTRIBUTES", failure.phase());
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
        assertFalse(Files.exists(copy.resolve(attributes)));
        assertEquals(candidate, headRevision(copy));
        assertEquals(before, Files.readString(copy.resolve(reportPath)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".GITATTRIBUTES", ".GitAttributes", "src/.gItAtTrIbUtEs", "src/.GITATTRIBUTES"})
    void sharedExecutionAndRepairGuardRejectsTrackedAliasesEvenWithNativeRules(String path) throws Exception {
        fixture(path, "*.java text eol=lf\n", false);
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertEquals(candidate, headRevision(copy));
    }

    @ParameterizedTest
    @CsvSource({".gitattributes,.GITATTRIBUTES", "src/.gitattributes,src/.GitAttributes"})
    void physicalCaseRenameCannotHideBehindCanonicalTreeAndIndex(String canonical, String variant) throws Exception {
        AuthorizedStepRollback authorized = fixture(canonical, "*.java text eol=lf\n", false);
        String indexBefore = runGit(copy, "ls-files", "--stage", "-z");
        Path intermediate = copy.resolve(".git/owned-attribute-case-rename");
        Files.move(copy.resolve(canonical), intermediate);
        Files.move(intermediate, copy.resolve(variant));
        assertEquals(indexBefore, runGit(copy, "ls-files", "--stage", "-z"));
        try (var entries = Files.newDirectoryStream(copy.resolve(variant).getParent())) {
            assertTrue(StreamSupport.stream(entries.spliterator(), false)
                    .anyMatch(path -> path.getFileName().toString().equals(Path.of(variant).getFileName().toString())));
        }
        String before = Files.readString(copy.resolve(reportPath));
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized));
        assertEquals(before, Files.readString(copy.resolve(reportPath)));
        assertEquals(candidate, headRevision(copy));
    }

    @ParameterizedTest
    @CsvSource({".gitattributes,.GITATTRIBUTES", "src/.gitattributes,src/.GitAttributes"})
    void indexAliasCannotReplaceCanonicalRevisionAndPhysicalFile(String canonical, String variant) throws Exception {
        AuthorizedStepRollback authorized = fixture(canonical, "*.java text eol=lf\n", false);
        String blob = runGit(copy, "rev-parse", candidate + ":" + canonical).trim();
        runGit(copy, "update-index", "--force-remove", "--", canonical);
        runGit(copy, "update-index", "--add", "--cacheinfo", "100644," + blob + "," + variant);
        assertTrue(Files.isRegularFile(copy.resolve(canonical)));
        assertTrue(runGit(copy, "ls-files", "--stage").contains("\t" + variant + "\n"));
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized));
        assertEquals(candidate, headRevision(copy));
    }

    @ParameterizedTest
    @ValueSource(strings = {"docs/[attr]*.txt text eol=lf", "\"docs/[attr]*.txt\" text eol=lf",
            "[attr] text eol=lf", "\"docs/[attr]*.txt\"text eol=lf"})
    void legitimateGlobIsNotAMacroAndAllowsExactRollback(String rule) throws Exception {
        AuthorizedStepRollback authorized = fixture(".gitattributes", rule + "\n", false);
        if (rule.startsWith("docs/") || rule.startsWith("\"docs/")) {
            assertEquals("docs/attr.txt: eol: lf", runGit(copy, "check-attr", "eol", "--", "docs/attr.txt").trim());
        }
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate);
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[attr]custom text eol=lf", "  [attr]custom text eol=lf",
            "\"[attr]custom\" text eol=lf", "\"\\133attr]custom\" text eol=lf"})
    void genuineMacrosRemainRejectedIncludingQuotedAndOctalPrefixes(String definition) throws Exception {
        fixture(".gitattributes", definition + "\n*.txt custom\n", false);
        assertEquals("docs/attr.txt: eol: lf", runGit(copy, "check-attr", "eol", "--", "docs/attr.txt").trim());
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"*.java\"filter=unset", "\"docs/a b.txt\" filter=unspecified",
            "\"docs/a\\tb.txt\" filter=unset", "*.java ident", "*.unmatched working-tree-encoding=UTF-16"})
    void quotedPatternsDoNotHideUnsupportedConversionTokens(String rule) throws Exception {
        fixture(".gitattributes", rule + "\n", false);
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
    }

    static Stream<Arguments> bomRules() {
        return Stream.of(".gitattributes", "area/.gitattributes").flatMap(path -> Stream.of(
                "*.unmatched filter=unset\n", "\"*.unmatched\"filter=unset\n",
                "[attr]custom text eol=lf\n*.unmatched custom\n",
                "\"[attr]custom\"filter=unset\n*.unmatched custom\n",
                "*.java text eol=lf\n").map(rule -> Arguments.of(path, "\ufeff" + rule)));
    }

    @ParameterizedTest
    @MethodSource("bomRules")
    void bomDefinitionsAreRejectedBySharedGuardAndUserRollbackBeforeMutation(String path, String rules)
            throws Exception {
        AuthorizedStepRollback authorized = fixture(path, rules, false);
        String probe = path.substring(0, path.lastIndexOf('/') + 1) + "probe.unmatched";
        String attribute = rules.contains("filter=") ? "filter" : "eol";
        String actual = runGit(copy, "check-attr", attribute, "--", probe);
        if (rules.contains("[attr]") && path.contains("/")) {
            // Git 不允许嵌套文件定义宏；仍须按本轮保守策略拒绝其 BOM，而不是声称宏已生效。
            assertTrue(actual.contains("not allowed"));
        } else if (rules.contains("filter=")) {
            assertTrue(actual.contains(": filter: unset"));
        } else if (rules.contains("[attr]")) {
            assertTrue(actual.contains(": eol: lf"));
        }
        runGit(copy, "config", "filter.unset.clean", "printf hit >> .git/filter-ran; cat");
        runGit(copy, "config", "filter.unset.smudge", "printf hit >> .git/filter-ran; cat");
        String before = Files.readString(copy.resolve(reportPath));
        assertAll(
                () -> assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                        new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate)),
                () -> assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized)),
                () -> assertFalse(Files.exists(copy.resolve(".git/filter-ran")), "FILTER_SIDE_EFFECT"),
                () -> assertEquals(candidate, headRevision(copy)),
                () -> assertEquals(before, Files.readString(copy.resolve(reportPath))));
    }

    static Stream<Arguments> bomIntermediateCases() {
        return Stream.of(".gitattributes", "area/.gitattributes").flatMap(path -> Stream.of("unset", "unspecified")
                .flatMap(driver -> Stream.of("clean", "process", "smudge")
                .flatMap(kind -> Stream.of(false, true).map(automatic -> Arguments.of(path, driver, kind, automatic)))));
    }

    @ParameterizedTest
    @MethodSource("bomIntermediateCases")
    void bomRulesCannotExecuteFiltersInIntermediateUserOrAutomaticRestoration(String attributes, String driver,
            String kind, boolean automatic) throws Exception {
        AuthorizedStepRollback authorized = fixture(attributes, "\ufeff\"src/*\"filter=" + driver + "\n",
                automatic, true);
        runGit(copy, "config", "filter." + driver + "." + kind, kind.equals("process")
                ? "printf hit >> .git/filter-ran; exit 1" : "printf hit >> .git/filter-ran; cat");
        // 两个端点视图均无生效 filter，不能只依赖端点 check-attr 或恢复后的阻塞来证明中间写入安全。
        assertEquals("", runGit(copy, "--attr-source=" + baseline, "check-attr", "--all", "--", reportPath));
        String before = Files.readString(copy.resolve(reportPath));
        assertAll(
                () -> assertEquals("BASELINE_ATTRIBUTES", assertThrows(WorkingCopyRollbackException.class,
                        () -> adapter.restoreBaseline(authorized)).phase()),
                () -> assertFalse(Files.exists(copy.resolve(".git/filter-ran")), "FILTER_SIDE_EFFECT"),
                () -> assertEquals(candidate, headRevision(copy)),
                () -> assertEquals(before, Files.readString(copy.resolve(reportPath))),
                () -> assertEquals("# inert Candidate rules\n", Files.readString(copy.resolve(attributes))),
                () -> assertEquals(baseline, headRevision(source)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\ufeff# inert\n", "# inert\n\ufeff*.java text\n", "*.java text\0\n",
            "*.java text\ufffd\n", "*.unused\rfilter=unset\n", "*.unused\u000bfilter=unset\n",
            "*.unused\u000cfilter=unset\n", "*.unused\u007ffilter=unset\n"})
    void ambiguousAttributeTextFailsClosedEvenWithoutAnActiveConversion(String rules) throws Exception {
        fixture(".gitattributes", rules, false);
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")), "FILTER_SIDE_EFFECT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0085", "\u2028", "\u2029"})
    void conversionNameCannotBeHiddenByNonGitLineSeparatorsInItsValue(String separator) throws Exception {
        fixture(".gitattributes", "*.unmatched filter=driver" + separator + "value\n", false);
        String actual = runGit(copy, "check-attr", "filter", "--", "probe.unmatched");
        assertTrue(actual.contains(": filter: driver" + separator + "value"));
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE", "invalid-UTF-8"})
    void nonUtf8AttributeBlobsAreRejectedWithoutConversion(String encoding) throws Exception {
        fixture(".gitattributes", "# inert UTF-8\n", false);
        byte[] bytes = encoding.equals("invalid-UTF-8")
                ? new byte[] {'#', ' ', (byte) 0xff, '\n'}
                : "# inert\n".getBytes(Charset.forName(encoding));
        Files.write(copy.resolve(".gitattributes"), bytes);
        runGit(copy, "add", "--", ".gitattributes");
        commit(copy, "Owned unsupported encoding fixture");
        candidate = headRevision(copy);
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")), "FILTER_SIDE_EFFECT");
        assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(copy.resolve(".gitattributes"))));
    }

}
