package com.ayywl.delveforge.infrastructure.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.headRevision;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.runGit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用合成仓库核对真实受控 Git 的上下文契约，不访问用户的 Plan、仓库或副本。 */
class GitPatchContextContractIntegrationTest {
    private static final String PATH = "src/ExpenseController.java";
    private static final String HEADER = "--- a/" + PATH + "\n+++ b/" + PATH + "\n";
    private final GitTestRepositories repositories = new GitTestRepositories();

    @AfterEach
    void cleanUpOwnedRepositories() throws Exception {
        repositories.deleteAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("patches")
    void controlledGitMatchesTheContextContract(String name, String original, String patch, int expectedExit)
            throws Exception {
        Map<String, String> files = original == null ? Map.of(".gitattributes", "* -text\n")
                : Map.of(".gitattributes", "* -text\n", PATH, original);
        Path repository = repositories.createCommitted("source", files);
        String baseline = headRevision(repository);
        Path index = repository.resolve(".git/delveforge-index-contract.tmp");
        Path patchFile = repository.resolve(".git/context-contract.diff");
        Files.writeString(patchFile, patch);
        GitWorkspaceAdapter adapter = new GitWorkspaceAdapter(repositories.directory("managed"));

        assertTrue(adapter.mutationGitResult(repository, index, "read-tree", baseline).succeeded());
        WorkspaceCommandRunner.Result result = adapter.mutationGitResult(repository, index,
                "apply", "--cached", "--whitespace=nowarn", "--", patchFile.toString());
        assertEquals(expectedExit, result.exitCode(), result.output());
        if (expectedExit == 0) {
            assertTrue(adapter.mutationGitResult(repository, index, "write-tree").succeeded());
        } else {
            assertTrue(result.output().startsWith("error:"));
        }
        assertEquals(baseline, headRevision(repository));
        assertEquals("", runGit(repository, "status", "--porcelain"));
    }

    static Stream<Arguments> patches() {
        String original = "one\ntwo\nthree\n";
        return Stream.concat(Stream.concat(reportedPatches(), fileLifecyclePatches()), Stream.of(
                Arguments.of("only leading context is insufficient", original,
                        HEADER + "@@ -1,2 +1,2 @@\n one\n-two\n+second\n", 1),
                Arguments.of("file beginning with trailing context", original,
                        HEADER + "@@ -1,2 +1,2 @@\n-one\n+first\n two\n", 0),
                Arguments.of("file end without context", original,
                        HEADER + "@@ -3 +3 @@\n-three\n+third\n", 0),
                Arguments.of("whole file replacement", original,
                        HEADER + "@@ -1,3 +1,2 @@\n-one\n-two\n-three\n+first\n+last\n", 0),
                Arguments.of("append", original, HEADER + "@@ -3,0 +4 @@\n+added\n", 0),
                Arguments.of("middle insertion without context", original,
                        HEADER + "@@ -1,0 +2 @@\n+added\n", 1),
                Arguments.of("prepend without context", original, HEADER + "@@ -0,0 +1 @@\n+added\n", 1),
                Arguments.of("empty existing file", "", HEADER + "@@ -0,0 +1 @@\n+added\n", 0),
                Arguments.of("new file", null,
                        "--- /dev/null\n+++ b/" + PATH + "\n@@ -0,0 +1 @@\n+new\n", 0),
                Arguments.of("complete deletion", original,
                        "--- a/" + PATH + "\n+++ /dev/null\n@@ -1,3 +0,0 @@\n-one\n-two\n-three\n", 0),
                Arguments.of("CRLF interior context", "one\r\ntwo\r\nthree\r\n",
                        HEADER + "@@ -1,3 +1,3 @@\n one\r\n-two\r\n+second\r\n three\r\n", 0),
                Arguments.of("no final newline", "one\ntwo",
                        HEADER + "@@ -1,2 +1,2 @@\n one\n-two\n\\ No newline at end of file\n+second\n", 0)));
    }

    private static Stream<Arguments> fileLifecyclePatches() {
        return Stream.of("\n", "\r\n").flatMap(newline -> {
            String original = "one" + newline + "two" + newline;
            String createHeader = "--- /dev/null\n+++ b/" + PATH + "\n";
            String deleteHeader = "--- a/" + PATH + "\n+++ /dev/null\n";
            return Stream.of(
                    Arguments.of("multi-hunk creation newline " + newline.length(), null,
                            createHeader + "@@ -0,0 +1 @@\n+one" + newline
                                    + "@@ -0,0 +2 @@\n+two" + newline, 1),
                    Arguments.of("multi-hunk deletion newline " + newline.length(), original,
                            deleteHeader + "@@ -1 +0,0 @@\n-one" + newline
                                    + "@@ -2 +0,0 @@\n-two" + newline, 1),
                    Arguments.of("single-hunk creation newline " + newline.length(), null,
                            createHeader + "@@ -0,0 +1,2 @@\n+one" + newline + "+two" + newline, 0),
                    Arguments.of("single-hunk deletion newline " + newline.length(), original,
                            deleteHeader + "@@ -1,2 +0,0 @@\n-one" + newline + "-two" + newline, 0),
                    Arguments.of("existing file multiple hunks newline " + newline.length(),
                            original + "three" + newline,
                            HEADER + "@@ -1,2 +1,3 @@\n-one" + newline + "+first" + newline
                                    + "+extra" + newline + " two" + newline
                                    + "@@ -3 +4 @@\n-three" + newline + "+third" + newline, 0));
        });
    }

    private static Stream<Arguments> reportedPatches() {
        return Stream.of("\n", "\r\n").flatMap(newline -> {
            String prefix = IntStream.rangeClosed(1, 26).mapToObj(i -> "// line " + i + newline)
                    .reduce("", String::concat);
            String annotations = "@RestController" + newline + "@RequestMapping(\"/expenses\")" + newline
                    + "@SecurityRequirement(name = \"bearerAuth\")" + newline;
            String suffix = "public class ExpenseController {" + newline + "}" + newline + "// tail" + newline;
            String changes = "-@RestController" + newline + "-@RequestMapping(\"/expenses\")" + newline
                    + "-@SecurityRequirement(name = \"bearerAuth\")" + newline + "+@Deprecated" + newline;
            String context = IntStream.rangeClosed(24, 26).mapToObj(i -> " // line " + i + newline)
                    .reduce("", String::concat);
            String trailing = " public class ExpenseController {" + newline + " }" + newline + " // tail" + newline;
            return Stream.of(
                    Arguments.of("reported line 27 without context " + newline.length(), prefix + annotations + suffix,
                            HEADER + "@@ -27,3 +27,1 @@\n" + changes, 1),
                    Arguments.of("same change with context " + newline.length(), prefix + annotations + suffix,
                            HEADER + "@@ -24,9 +24,7 @@\n" + context + changes + trailing, 0));
        });
    }
}
