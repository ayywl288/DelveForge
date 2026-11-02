package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryCompatibilityIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"npm", "pnpm"})
    void standaloneVueProjectCanBePreparedWithoutInventingAMavenBackend(String manager) throws Exception {
        String version = manager.equals("npm") ? "9.9.4" : "9.11.0";
        String lock = manager.equals("npm") ? "package-lock.json" : "pnpm-lock.yaml";
        String manifest = "{\"packageManager\":\"" + manager + "@" + version
                + "\",\"dependencies\":{\"vue\":\"3.5.17\"},\"devDependencies\":{\"vite\":\"6.3.5\","
                + "\"typescript\":\"5.8.3\"},\"scripts\":{\"build\":\"vite build\"}}";
        Path source = repositories.createCommitted("vue-only", Map.of("package.json", manifest, lock, "{}",
                "src/main.ts", "export const value = 1;\n"));
        String baseline = GitTestRepositories.headRevision(source);
        var assessment = compatibility.inspect(source, baseline).report();
        assertTrue(assessment.supported());
        assertTrue(assessment.modules().isEmpty());
        var workspace = new GitWorkspaceAdapter(repositories.directory("managed"));
        var copy = workspace.provision(new WorkspaceRef(source.toString()), baseline, "copy");
        assertEquals(baseline, copy.revision());
        assertEquals("", GitTestRepositories.runGit(Path.of(copy.workspace().value()), "status", "--porcelain"));
        workspace.discard(copy);
        GitTestRepositories.writeFile(source, manager.equals("npm") ? "pnpm-lock.yaml" : "package-lock.json", "{}");
        GitTestRepositories.runGit(source, "add", ".");
        GitTestRepositories.commit(source, "conflicting locks");
        assertFalse(compatibility.inspect(source, GitTestRepositories.headRevision(source)).report().supported());
    }

    private static final String POM = "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
            + "<artifactId>compatible</artifactId><version>1</version><properties><maven.compiler.release>17"
            + "</maven.compiler.release></properties></project>";
    private final GitTestRepositories repositories = new GitTestRepositories();
    private final GitRepositoryCompatibility compatibility = new GitRepositoryCompatibility(
            new DeterministicRepositorySecretPolicy());

    @AfterEach
    void cleanup() throws Exception {
        repositories.deleteAll();
    }

    @Test
    void preparesOnlyProvenCompiledOutputAndCopiedResourcesWithExactOriginalParent() throws Exception {
        Path source = repositories.createCommitted("original", Map.of(".gitattributes", "* -text\n", "backend/pom.xml", POM,
                "backend/src/main/java/Hello.java", "public class Hello {}\n",
                "backend/src/main/resources/config.txt", "unchanged resource\r\n"));
        Path output = source.resolve("backend/target/classes");
        Files.createDirectories(output);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(),
                source.resolve("backend/src/main/java/Hello.java").toString()));
        Files.copy(source.resolve("backend/src/main/resources/config.txt"), output.resolve("config.txt"));
        GitTestRepositories.runGit(source, "add", ".");
        GitTestRepositories.commit(source, "tracked compiler output");
        String original = GitTestRepositories.headRevision(source);
        var before = GitTestRepositories.snapshot(source);
        var assessment = compatibility.inspect(source, original).report();
        assertTrue(assessment.supported());
        assertEquals("backend", assessment.mavenDirectory());
        assertEquals(17, assessment.buildJdk());
        assertEquals(2, assessment.generatedPaths().size());

        var adapter = new GitWorkspaceAdapter(repositories.directory("managed"));
        var prepared = adapter.provision(new WorkspaceRef(source.toString()), original, "copy");
        Path copy = Path.of(prepared.workspace().value());
        assertNotEquals(original, prepared.revision());
        assertEquals(original, prepared.provenance().originalRevision());
        assertEquals(prepared.revision() + " " + original,
                GitTestRepositories.runGit(copy, "rev-list", "--parents", "-n", "1", prepared.revision()).trim());
        assertFalse(Files.exists(copy.resolve("backend/target/classes/Hello.class")));
        assertArrayEquals(Files.readAllBytes(source.resolve("backend/src/main/resources/config.txt")),
                Files.readAllBytes(copy.resolve("backend/src/main/resources/config.txt")));
        assertTrue(Files.readString(copy.resolve(".gitignore")).contains("/backend/target/"));
        assertEquals("", GitTestRepositories.runGit(copy, "status", "--porcelain"));
        assertEquals(before, GitTestRepositories.snapshot(source));
        assertEquals(original, GitTestRepositories.headRevision(source));
        assertTrue(compatibility.inspect(copy, prepared.revision()).report().supported());
        adapter.discard(prepared);
        assertFalse(Files.exists(copy));
    }

    @Test
    void dotFileIgnoreDoesNotStandInForIgnoringTheGeneratedDirectory() throws Exception {
        Path source = repositories.createCommitted("original", Map.of(".gitattributes", "* -text\n",
                "backend/pom.xml", POM, "backend/src/main/resources/config.txt", "resource\n",
                "backend/target/classes/config.txt", "resource\n"));
        GitTestRepositories.writeFile(source, ".gitignore", "**/.*\n");
        GitTestRepositories.runGit(source, "add", "-f", "--", ".gitignore");
        GitTestRepositories.commit(source, "ignore dot files only");
        String original = GitTestRepositories.headRevision(source);
        var before = GitTestRepositories.snapshot(source);
        Path managed = repositories.directory("managed");
        var adapter = new GitWorkspaceAdapter(managed);
        var prepared = adapter.provision(new WorkspaceRef(source.toString()), original, "copy");
        Path copy = Path.of(prepared.workspace().value());

        assertEquals("**/.*\n/backend/target/\n", Files.readString(copy.resolve(".gitignore")));
        assertFalse(CompatibilityGit.text(copy, null, Map.of(), "check-ignore", "--no-index", "--",
                "backend/target/rebuilt.txt").isEmpty());
        new GitWorkspaceAdapter(managed).requirePreparationProvenance(prepared.workspace(), prepared.provenance());
        assertEquals(before, GitTestRepositories.snapshot(source));
        assertEquals(original, GitTestRepositories.headRevision(source));
        adapter.discard(prepared);
    }

    @ParameterizedTest
    @ValueSource(strings = {"service[1]", "service]1[", "service !# [1]"})
    void generatedIgnoreRulesTreatModuleNamesAsLiteralPaths(String module) throws Exception {
        Path source = repositories.createCommitted("original", Map.of(".gitattributes", "* -text\n",
                module + "/pom.xml", POM, module + "/src/main/resources/config.txt", "resource\n",
                module + "/target/classes/config.txt", "resource\n",
                "service1/target/customer.json", "{\"business\":true}\n"));
        String original = GitTestRepositories.headRevision(source);
        var before = GitTestRepositories.snapshot(source);
        Path managed = repositories.directory("managed");
        var adapter = new GitWorkspaceAdapter(managed);
        var prepared = adapter.provision(new WorkspaceRef(source.toString()), original, "copy");
        Path copy = Path.of(prepared.workspace().value());

        assertFalse(CompatibilityGit.text(copy, null, Map.of(), "check-ignore", "--no-index", "--",
                module + "/target/rebuilt.txt").isEmpty());
        assertEquals("", CompatibilityGit.text(copy, null, Map.of(), "check-ignore", "--no-index", "--",
                "service1/target/customer.json"));
        assertArrayEquals(Files.readAllBytes(source.resolve("service1/target/customer.json")),
                Files.readAllBytes(copy.resolve("service1/target/customer.json")));
        new GitWorkspaceAdapter(managed).requirePreparationProvenance(prepared.workspace(), prepared.provenance());
        assertEquals("", GitTestRepositories.runGit(copy, "status", "--porcelain"));
        assertEquals(before, GitTestRepositories.snapshot(source));
        assertEquals(original, GitTestRepositories.headRevision(source));
        adapter.discard(prepared);
    }

    @Test
    void cleanNestedMavenPreservesIdentityAndBinaryResources() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("service/pom.xml", POM,
                "service/src/main/java/Hello.java", "class Hello {}\n"));
        byte[] image = new byte[] { (byte) 137, 80, 78, 71, 0, (byte) 255 };
        Files.write(source.resolve("logo.png"), image);
        GitTestRepositories.runGit(source, "add", ".");
        GitTestRepositories.commit(source, "binary resource");
        String original = GitTestRepositories.headRevision(source);
        var assessment = compatibility.inspect(source, original).report();
        assertTrue(assessment.supported());
        assertEquals(1, assessment.opaqueFiles());
        assertEquals("service", assessment.mavenDirectory());
        var adapter = new GitWorkspaceAdapter(repositories.directory("managed"));
        var prepared = adapter.provision(new WorkspaceRef(source.toString()), original, "copy");
        assertEquals(original, prepared.revision());
        assertEquals("IDENTITY", prepared.provenance().policyVersion());
        assertArrayEquals(image, Files.readAllBytes(Path.of(prepared.workspace().value()).resolve("logo.png")));
        adapter.discard(prepared);
    }

    @Test
    void recognizesReactorIncludingLegitimateTargetNamedModule() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM.replace("</project>",
                "<packaging>pom</packaging><modules><module>target</module><module>api</module></modules></project>"),
                "target/pom.xml", POM, "target/src/main/java/Hello.java", "class Hello {}",
                "api/pom.xml", POM));
        var report = compatibility.inspect(source, GitTestRepositories.headRevision(source)).report();
        assertTrue(report.supported(), report.issues().toString());
        assertEquals(3, report.modules().size());
        assertTrue(report.generatedPaths().isEmpty());
    }

    @Test
    void ambiguousTargetContentIsPreservedAndPreparationFailsBeforeAllocation() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM,
                "target/customer-records.json", "{\"business\":true}"));
        String original = GitTestRepositories.headRevision(source);
        var before = GitTestRepositories.snapshot(source);
        var report = compatibility.inspect(source, original).report();
        assertFalse(report.supported());
        assertTrue(report.generatedPaths().isEmpty());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("GENERATED_OUTPUT_AMBIGUITY")));
        Path managed = repositories.directory("managed");
        assertThrows(AgentException.class, () -> new GitWorkspaceAdapter(managed)
                .provision(new WorkspaceRef(source.toString()), original, "copy"));
        assertFalse(Files.exists(managed.resolve("copy")));
        assertEquals(before, GitTestRepositories.snapshot(source));
    }

    @Test
    void opaqueResourcesMayExceedOldTotalAndEditableBudgetsButRemainBounded() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM));
        byte[] asset = new byte[6291456];
        asset[0] = (byte) 255;
        for (int i = 0; i < 3; i++) {
            Files.write(source.resolve("asset-" + i + ".bin"), asset);
        }
        GitTestRepositories.runGit(source, "add", ".");
        GitTestRepositories.commit(source, "large immutable resources");
        var report = compatibility.inspect(source, GitTestRepositories.headRevision(source)).report();
        assertTrue(report.supported());
        assertTrue(report.sourceBytes() > 16777216);
        assertEquals(3, report.opaqueFiles());
        Files.write(source.resolve("oversized.bin"), new byte[GitRepositoryCompatibility.MAX_BASELINE_FILE_BYTES + 1]);
        GitTestRepositories.runGit(source, "add", ".");
        GitTestRepositories.commit(source, "exceeds bounded file size");
        report = compatibility.inspect(source, GitTestRepositories.headRevision(source)).report();
        assertFalse(report.supported());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("SOURCE_LIMIT")));
    }

    @Test
    void recognizedCredentialTextIsImmutableWithoutLeakingItsValue() throws Exception {
        String canary = "CANARY-private-signing-material-1234567890";
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM,
                "src/main/resources/application.properties", "jwt.secret=" + canary + "\n"));
        var report = compatibility.inspect(source, GitTestRepositories.headRevision(source)).report();
        assertTrue(report.supported());
        assertTrue(report.immutablePaths().contains("src/main/resources/application.properties"));
        assertFalse(report.toString().contains(canary));
    }

    @Test
    void sensitivePathsArePreservedOnlyWhileSpecialModesRemainExplicitBlockers() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM, ".env", "PRIVATE=canary"));
        var inspection = compatibility.inspect(source, GitTestRepositories.headRevision(source));
        assertTrue(inspection.report().supported());
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, inspection.resources().get(".env").kind());
        assertFalse(inspection.resources().get(".env").executionAvailable());
        assertFalse(inspection.resources().get(".env").modelVisible());
        assertTrue(inspection.report().issues().stream().anyMatch(issue -> issue.code().equals("PRESERVED_ONLY_RESOURCE")));
        assertEquals("PRIVATE=canary", GitTestRepositories.runGit(source, "show", "HEAD:.env").trim());
        String object = GitTestRepositories.runGit(source, "rev-parse", "HEAD:pom.xml").trim();
        GitTestRepositories.runGit(source, "update-index", "--add", "--cacheinfo", "120000," + object + ",link");
        GitTestRepositories.commit(source, "unsafe symbolic entry");
        assertTrue(compatibility.inspect(source, GitTestRepositories.headRevision(source)).report().issues()
                .stream().anyMatch(issue -> issue.code().equals("GIT_TYPE_UNSUPPORTED")));
    }

    @Test
    void caseConflictsAreDetectedFromGitObjectsOnWindowsToo() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM));
        String object = GitTestRepositories.runGit(source, "rev-parse", "HEAD:pom.xml").trim();
        GitTestRepositories.runGit(source, "update-index", "--add", "--cacheinfo", "100644," + object + ",Dir/A.txt");
        GitTestRepositories.runGit(source, "update-index", "--add", "--cacheinfo", "100644," + object + ",dir/B.txt");
        GitTestRepositories.commit(source, "case-conflicting directories");
        assertTrue(compatibility.inspect(source, GitTestRepositories.headRevision(source)).report().issues()
                .stream().anyMatch(issue -> issue.code().equals("GIT_PATH_UNSUPPORTED")));
    }

    @Test
    void withholdingContentCannotBypassGitTransformOrReservedDirectoryRules() throws Exception {
        Path source = repositories.createCommitted("content-cannot-grant-structural-access", Map.of("pom.xml", POM,
                ".gitattributes", "* filter=unsafe\n# password=private-fixture\n"));
        var report = compatibility.inspect(source, GitTestRepositories.headRevision(source)).report();
        assertFalse(report.supported());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("GIT_ATTRIBUTES_UNSUPPORTED")));
        assertFalse(GitRepositoryCompatibility.safePath(".npmrc/hidden.txt"));
        assertFalse(GitRepositoryCompatibility.safePath(".env/.env"));
        assertTrue(GitRepositoryCompatibility.safePath(".env.example"));
    }
    @Test
    void pomAndTargetDirectoriesInsideTestResourcesRemainImmutableOriginalAssets() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM,
                "src/test/resources/template/pom.xml", "intentionally invalid POM fixture",
                "src/test/resources/template/target/business.json", "legitimate test asset"));
        String original = GitTestRepositories.headRevision(source);
        var report = compatibility.inspect(source, original).report();
        assertTrue(report.supported(), report.issues().toString());
        assertTrue(report.generatedPaths().isEmpty());
        var receipt = new GitWorkspaceAdapter(repositories.directory("managed"))
                .provision(new WorkspaceRef(source.toString()), original, "copy");
        assertEquals(original, receipt.revision());
        assertEquals("legitimate test asset", Files.readString(Path.of(receipt.workspace().value())
                .resolve("src/test/resources/template/target/business.json")));
    }

    @Test
    void restartRejectsMissingReceiptAndRetryUsesNewOwnedCopyWithoutAdoptingOldDirectory() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM,
                "src/main/resources/a.txt", "resource", "target/classes/a.txt", "resource"));
        String a = GitTestRepositories.headRevision(source);
        Path root = repositories.directory("managed");
        var first = new GitWorkspaceAdapter(root).provision(new WorkspaceRef(source.toString()), a, "first");
        var restarted = new GitWorkspaceAdapter(root);
        restarted.requirePreparationProvenance(first.workspace(), first.provenance());
        Path marker = Path.of(first.workspace().value()).resolve(".git/delveforge-preparation.json");
        byte[] receipt = Files.readAllBytes(marker);
        Files.delete(marker);
        assertThrows(com.ayywl.delveforge.application.port.workspace.WorkingCopyBasisChangedException.class,
                () -> restarted.requirePreparationProvenance(first.workspace(), first.provenance()));
        assertThrows(com.ayywl.delveforge.application.port.workspace.WorkspaceException.class,
                () -> restarted.provision(new WorkspaceRef(source.toString()), a, "first"));
        Files.write(marker, receipt);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var second = pool.submit(() -> restarted.provision(new WorkspaceRef(source.toString()), a, "second"));
            var third = pool.submit(() -> restarted.provision(new WorkspaceRef(source.toString()), a, "third"));
            var b = second.get();
            var c = third.get();
            assertEquals(first.revision(), b.revision());
            assertEquals(b.revision(), c.revision());
            assertNotEquals(b.workspace(), c.workspace());
            restarted.discard(b);
            restarted.discard(c);
        }
        restarted.discard(first);
        assertEquals(a, GitTestRepositories.headRevision(source));
    }

    @Test
    void submoduleEntriesAreBlockedAndExecutableBlobModesRemainExact() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM, "script.sh", "#!/bin/sh\nexit 0\n"));
        GitTestRepositories.runGit(source, "update-index", "--chmod=+x", "script.sh");
        GitTestRepositories.commit(source, "executable mode");
        String a = GitTestRepositories.headRevision(source);
        var prepared = new GitWorkspaceAdapter(repositories.directory("managed"))
                .provision(new WorkspaceRef(source.toString()), a, "copy");
        assertEquals(GitTestRepositories.runGit(source, "ls-tree", a, "script.sh"),
                GitTestRepositories.runGit(Path.of(prepared.workspace().value()), "ls-tree", prepared.revision(), "script.sh"));
        GitTestRepositories.runGit(source, "update-index", "--add", "--cacheinfo", "160000," + a + ",submodule");
        GitTestRepositories.commit(source, "gitlink");
        assertTrue(compatibility.inspect(source, GitTestRepositories.headRevision(source)).report().issues().stream()
                .anyMatch(issue -> issue.code().equals("GIT_TYPE_UNSUPPORTED") && issue.path().equals("submodule")));
    }

    @Test
    void genuinePublicRepositoriesReceivePinnedDiagnosticsWithoutHostBuilds() throws Exception {
        String evidence = System.getProperty("delveforge.compatibility.evidence");
        org.junit.jupiter.api.Assumptions.assumeTrue(evidence != null, "Additional public clone matrix is separate");
        Map<String, Object> results = new java.util.LinkedHashMap<>();
        for (String name : java.util.List.of("spring-petclinic", "spring-multi-module")) {
            Path source = Path.of(evidence).resolve(name);
            String a = GitTestRepositories.headRevision(source);
            var before = GitTestRepositories.snapshot(source);
            var report = compatibility.inspect(source, a).report();
            if (name.equals("spring-multi-module")) {
                assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("MAVEN_ROOT_AMBIGUOUS")));
            } else {
                assertTrue(report.supported(), report.issues().toString());
                assertEquals(".", report.mavenDirectory());
                assertEquals(17, report.buildJdk());
            }
            assertEquals(before, GitTestRepositories.snapshot(source));
            results.put(name, Map.of("revision", a, "report", report, "sourceUnchanged", true,
                    "hostProjectExecutions", 0));
        }
        Files.writeString(Path.of(evidence).resolve("public-repository-matrix.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @Test
    void generatedReportRequiresTrackedTestEvidenceAndIsNotJustAnXmlRootName() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM,
                "target/surefire-reports/TEST-Customer.xml", "<testsuite/>"));
        var blocked = compatibility.inspect(source, GitTestRepositories.headRevision(source)).report();
        assertTrue(blocked.generatedPaths().isEmpty());
        assertTrue(blocked.issues().stream().anyMatch(issue -> issue.code().equals("GENERATED_OUTPUT_AMBIGUITY")));
        Files.createDirectories(source.resolve("src/test/java/example"));
        Files.writeString(source.resolve("src/test/java/example/CustomerTest.java"), "package example; class CustomerTest {}\n");
        Files.delete(source.resolve("target/surefire-reports/TEST-Customer.xml"));
        Files.writeString(source.resolve("target/surefire-reports/TEST-example.CustomerTest.xml"),
                "<testsuite name=\"example.CustomerTest\" tests=\"1\" failures=\"0\" errors=\"0\"/>");
        GitTestRepositories.runGit(source, "add", ".");
        GitTestRepositories.commit(source, "recognizable test report");
        assertEquals(java.util.List.of("target/surefire-reports/TEST-example.CustomerTest.xml"),
                compatibility.inspect(source, GitTestRepositories.headRevision(source)).report().generatedPaths());
    }

    @Test
    void totalAndFileCountBudgetsAreCheckedUsingGitMetadataBeforeMaterialization() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM));
        byte[] resource = new byte[GitRepositoryCompatibility.MAX_BASELINE_FILE_BYTES];
        String object = CompatibilityGit.text(source, resource, Map.of(), "hash-object", "-w", "--stdin");
        for (int i = 0; i < 8; i++) {
            GitTestRepositories.runGit(source, "update-index", "--add", "--cacheinfo", "100644," + object + ",asset-" + i + ".bin");
        }
        GitTestRepositories.commit(source, "tree exceeds total source budget");
        assertTrue(compatibility.inspect(source, GitTestRepositories.headRevision(source)).report().issues().stream()
                .anyMatch(issue -> issue.code().equals("SOURCE_LIMIT")));
        Path many = repositories.createCommitted("many", Map.of("pom.xml", POM));
        String blob = GitTestRepositories.runGit(many, "rev-parse", "HEAD:pom.xml").strip();
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i <= GitRepositoryCompatibility.MAX_FILES; i++) {
            entries.append("100644 ").append(blob).append("\tentry-").append(i).append("\n");
        }
        CompatibilityGit.run(many, entries.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of(),
                "update-index", "--index-info");
        GitTestRepositories.commit(many, "tree exceeds file count budget");
        assertTrue(compatibility.inspect(many, GitTestRepositories.headRevision(many)).report().issues().stream()
                .anyMatch(issue -> issue.code().equals("SOURCE_LIMIT")));
    }

    @Test
    void externalBuildPrerequisitesAreDiagnosedWithoutRevealingRepositoryCredentials() throws Exception {
        Path source = repositories.createCommitted("original", Map.of("pom.xml", POM.replace("</project>",
                "<repositories><repository><id>private</id><url>https://private.invalid/repo</url></repository></repositories>"
                + "<dependencies><dependency><groupId>x</groupId><artifactId>x</artifactId><version>1</version>"
                + "<scope>system</scope><systemPath>/private/x.jar</systemPath></dependency></dependencies></project>")));
        var report = compatibility.inspect(source, GitTestRepositories.headRevision(source)).report();
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("CUSTOM_DEPENDENCY_REPOSITORY")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("LOCAL_BUILD_PREREQUISITE") && issue.blocking()));
        assertFalse(report.toString().contains("private.invalid"));
    }

    @Test
    void untouchedFinTrackIsAutomaticallyPreparedWithoutManualSourceChanges() throws Exception {
        String configured = System.getProperty("delveforge.compatibility.fintrack");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null, "Fresh public clone acceptance is separate");
        Path source = Path.of(configured).toAbsolutePath();
        String original = GitTestRepositories.headRevision(source);
        assertEquals("0e7f0bce854dee6f4d43582f8315bb45cec369eb", original);
        var before = GitTestRepositories.snapshot(source);
        var inspection = compatibility.inspect(source, original);
        assertTrue(inspection.report().supported(), inspection.report().issues().toString());
        assertTrue(inspection.report().generatedPaths().size() > 40);
        assertEquals("backend", inspection.report().mavenDirectory());
        assertTrue(inspection.report().immutablePaths().contains("backend/src/main/resources/application.properties"));
        var adapter = new GitWorkspaceAdapter(repositories.directory("managed-fintrack"));
        var prepared = adapter.provision(new WorkspaceRef(source.toString()), original, "copy");
        Path copy = Path.of(prepared.workspace().value());
        var normalized = compatibility.inspect(copy, prepared.revision());
        assertTrue(normalized.report().supported(), normalized.report().issues().toString());
        assertTrue(normalized.report().generatedPaths().isEmpty());
        assertNotEquals(original, prepared.revision());
        for (var entry : inspection.entries().entrySet()) {
            if (!prepared.provenance().removedPaths().contains(entry.getKey())
                    && !prepared.provenance().metadataPaths().contains(entry.getKey())) {
                assertEquals(entry.getValue(), normalized.entries().get(entry.getKey()), entry.getKey());
            }
        }
        adapter.requirePreparationProvenance(new WorkspaceRef(copy.toString()), prepared.provenance());
        assertEquals(before, GitTestRepositories.snapshot(source));
        Files.writeString(source.getParent().resolve("fintrack-preparation.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("original", original, "prepared", prepared.revision(), "provenance", prepared.provenance(),
                                "assessment", inspection.report(), "normalized", normalized.report(),
                                "sourceUnchanged", true)));
        adapter.discard(prepared);
    }

}
