package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnifiedDiffGitContractTest {
    private static final String PATH = "src/ExpenseController.java";
    private static final String HEADER = "--- a/" + PATH + "\n+++ b/" + PATH + "\n";
    private final UnifiedDiffValidator validator = new UnifiedDiffValidator(
            new ProposalPathPolicy(new DeterministicRepositorySecretPolicy()));

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void rejectsTheReportedLine27ReplacementWithoutTrailingContext(String newline) {
        String original = IntStream.rangeClosed(1, 26).mapToObj(i -> "// line " + i + newline)
                .reduce("", String::concat)
                + "@RestController" + newline + "@RequestMapping(\"/expenses\")" + newline
                + "@SecurityRequirement(name = \"bearerAuth\")" + newline
                + "public class ExpenseController {" + newline + "}" + newline;
        String patch = HEADER + "@@ -27,3 +27,1 @@\n"
                + "-@RestController" + newline + "-@RequestMapping(\"/expenses\")" + newline
                + "-@SecurityRequirement(name = \"bearerAuth\")" + newline + "+@Deprecated" + newline;

        assertThrows(AiGatewayException.class,
                () -> validator.validate(patch, Map.of(PATH, original), Set.of(PATH)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedHunks")
    void rejectsMissingEndAnchorsBeforeTheOldFileEnd(String name, String hunk) {
        assertThrows(AiGatewayException.class,
                () -> validator.validate(HEADER + hunk, Map.of(PATH, "one\ntwo\nthree\n"), Set.of(PATH)));
    }

    static Stream<Arguments> unsupportedHunks() {
        return Stream.of(
                Arguments.of("file beginning without context", "@@ -1 +1 @@\n-one\n+first\n"),
                Arguments.of("only leading context", "@@ -1,2 +1,2 @@\n one\n-two\n+second\n"),
                Arguments.of("middle insertion", "@@ -1,0 +2 @@\n+added\n"),
                Arguments.of("prepend without context", "@@ -0,0 +1 @@\n+added\n"),
                Arguments.of("earlier hunk cannot borrow later context",
                        "@@ -1 +1 @@\n-one\n+first\n@@ -2,2 +2,2 @@\n two\n-three\n+third\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedHunks")
    void retainsSupportedContextAndEndOfFileCases(String name, String original, String hunk) {
        assertEquals(List.of(PATH), validator.validate(HEADER + hunk, Map.of(PATH, original), Set.of(PATH)));
    }

    static Stream<Arguments> supportedHunks() {
        String original = "one\ntwo\nthree\n";
        return Stream.of(
                Arguments.of("interior context", original,
                        "@@ -1,3 +1,3 @@\n one\n-two\n+second\n three\n"),
                Arguments.of("file beginning", original, "@@ -1,2 +1,2 @@\n-one\n+first\n two\n"),
                Arguments.of("one trailing context line", original,
                        "@@ -2,2 +2,2 @@\n-two\n+second\n three\n"),
                Arguments.of("file end with context", original,
                        "@@ -2,2 +2,2 @@\n two\n-three\n+third\n"),
                Arguments.of("file end without context", original, "@@ -3 +3 @@\n-three\n+third\n"),
                Arguments.of("whole file replacement", original,
                        "@@ -1,3 +1,2 @@\n-one\n-two\n-three\n+first\n+last\n"),
                Arguments.of("append", original, "@@ -3,0 +4 @@\n+added\n"),
                Arguments.of("delete last line", original, "@@ -3 +2,0 @@\n-three\n"),
                Arguments.of("empty existing file", "", "@@ -0,0 +1 @@\n+added\n"),
                Arguments.of("CRLF context", "one\r\ntwo\r\nthree\r\n",
                        "@@ -1,3 +1,3 @@\n one\r\n-two\r\n+second\r\n three\r\n"),
                Arguments.of("no final newline", "one\ntwo",
                        "@@ -1,2 +1,2 @@\n one\n-two\n\\ No newline at end of file\n+second\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void retainsFileCreationAndCompleteDeletionWithoutContext(String newline) {
        String create = "--- /dev/null\n+++ b/" + PATH + "\n@@ -0,0 +1 @@\n+new" + newline;
        assertEquals(List.of(PATH), validator.validate(create, Map.of(), Set.of()));
        String delete = "--- a/" + PATH + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-old" + newline;
        assertEquals(List.of(PATH), validator.validate(delete, Map.of(PATH, "old" + newline), Set.of(PATH)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multipleFileLifecycleHunks")
    void rejectsMultipleHunksForCreationAndCompleteDeletion(String name, String original, String patch) {
        assertThrows(AiGatewayException.class, () -> validator.validate(patch,
                original == null ? Map.of() : Map.of(PATH, original),
                original == null ? Set.of() : Set.of(PATH)));
    }

    static Stream<Arguments> multipleFileLifecycleHunks() {
        return Stream.of("\n", "\r\n").flatMap(newline -> Stream.of(
                Arguments.of("multi-hunk creation newline " + newline.length(), null,
                        "--- /dev/null\n+++ b/" + PATH + "\n@@ -0,0 +1 @@\n+one" + newline
                                + "@@ -0,0 +2 @@\n+two" + newline),
                Arguments.of("multi-hunk deletion newline " + newline.length(), "one" + newline + "two" + newline,
                        "--- a/" + PATH + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-one" + newline
                                + "@@ -2 +0,0 @@\n-two" + newline)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void retainsMultipleHunksForExistingFileChanges(String newline) {
        String patch = HEADER + "@@ -1,2 +1,3 @@\n-one" + newline + "+first" + newline
                + "+extra" + newline + " two" + newline + "@@ -3 +4 @@\n-three" + newline + "+third" + newline;
        assertEquals(List.of(PATH), validator.validate(patch,
                Map.of(PATH, "one" + newline + "two" + newline + "three" + newline), Set.of(PATH)));
    }
}
