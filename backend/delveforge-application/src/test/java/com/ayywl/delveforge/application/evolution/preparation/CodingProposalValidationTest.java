package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.evolution.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodingProposalValidationTest {
    private static final ExecutionPreparationLimits LIMITS = new ExecutionPreparationLimits(
            2, 24, 48, 65536, 524288, 786432, 262144, 16, 16);
    private final DeterministicRepositorySecretPolicy secrets = new DeterministicRepositorySecretPolicy();
    private final ProposalPathPolicy paths = new ProposalPathPolicy(secrets);
    private final EvolutionPlanId planId = new EvolutionPlanId("plan");
    private final EvolutionStepId stepId = new EvolutionStepId("step");
    private final ExecutionPreparationBasis basis = new ExecutionPreparationBasis(planId, stepId, new WorkingCopyId("copy"), "A");
    private final EvolutionStep step = EvolutionStep.reconstitute(stepId, planId, "goal", "scope",
            List.of("change"), List.of(), List.of(VerificationCriterion.unsupported("criterion one"), VerificationCriterion.unsupported("criterion two")), EvolutionStepStatus.READY);
    private Map<String, String> checkedOriginals;
    private final WorkingCopyInspectionPort inspection = new WorkingCopyInspectionPort() {
        public void requireTrustedState(WorkspaceRef ref, WorkspaceRef source, String revision) { throw new AssertionError(); }
        public void requireSafePaths(WorkspaceRef ref, List<String> paths) {}
        public void requireExactSourceText(WorkspaceRef ref, String revision, Map<String, String> originals) {
            assertEquals("managed", ref.value());
            assertEquals("A", revision);
            checkedOriginals = Map.copyOf(originals);
        }
    };

    private CodingProposal proposal(String patch, List<String> targets, String revision) {
        return new CodingProposal(revision, "change", targets, patch,
                List.of(new CodingProposal.VerificationAction(CodingProposal.Action.TEST, "", List.of(1, 2))));
    }

    private ValidatedExecutionProposal validate(CodingProposal proposal, Map<String, String> visible,
            Set<String> redacted, Set<String> existing) {
        return new CodingProposalValidator(secrets, inspection, LIMITS).validate(proposal, basis, step,
                new WorkspaceRef("managed"), visible, redacted, existing);
    }

    private static String replace(String path) {
        return "--- a/" + path + "\n+++ b/" + path + "\n@@ -1 +1 @@\n-old\n+new\n";
    }

    private static String add(String path) {
        return "--- /dev/null\n+++ b/" + path + "\n@@ -0,0 +1 @@\n+new\n";
    }

    @Test void rejectsAncestorDescendantTargetsIncludingWindowsCaseAliases() {
        for (List<String> targets : List.of(List.of("src/New.java", "src/New.java/Child.java"),
                List.of("SRC/new.JAVA/Child.java", "src/New.java"))) {
            String patch = targets.stream().map(CodingProposalValidationTest::add).reduce("", String::concat);
            assertThrows(AiGatewayException.class, () -> validate(proposal(patch, targets, "A"),
                    Map.of(), Set.of(), Set.of()));
        }
        List<String> siblings = List.of("src/New.java", "src/Other.java");
        assertNotNull(validate(proposal(add(siblings.get(0)) + add(siblings.get(1)), siblings, "A"),
                Map.of(), Set.of(), Set.of()));
    }

    @Test void checksExactBlobTextOnlyForExistingModifiedTargetsAtTheInvocationRevision() {
        List<String> targets = List.of("src/A", "src/New.java");
        assertNotNull(validate(proposal(replace("src/A") + add("src/New.java"), targets, "A"),
                Map.of("src/A", "old\n", "src/Unused", "not modified\n"), Set.of(), Set.of("src/A", "src/Unused")));
        assertEquals(Map.of("src/A", "old\n"), checkedOriginals);
    }

    @Test void rejectsUnsafeInternalAndWindowsAliasedPaths() {
        for (String path : List.of("../x", "/x", "C:/x", "x/../y", "x\\y", "./x", "x//y",
                ".git/config", ".GIT/hooks/x", ".codex/data", ".agents/x", ".openai/x",
                "x:stream", "CON.java", "x/nul", "x. /a", "x./a", "x/a ", "x/\u0000a", ".env")) {
            assertThrows(AiGatewayException.class, () -> paths.requireSafe(path), path);
        }
        assertEquals("src/正常.java", paths.requireSafe("src/正常.java"));
    }

    @Test void normalModificationAdditionAndDeletionAreExactTextPatches() {
        assertEquals(List.of("src/A"), new UnifiedDiffValidator(paths).validate(replace("src/A"),
                Map.of("src/A", "old\n"), Set.of("src/A")));
        String create = "--- /dev/null\n+++ b/test/New.java\n@@ -0,0 +1,2 @@\n+line one\n+line two\n";
        assertEquals(List.of("test/New.java"), new UnifiedDiffValidator(paths).validate(create, Map.of(), Set.of()));
        String delete = "--- a/src/A\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-one\n-two\n";
        assertEquals(List.of("src/A"), new UnifiedDiffValidator(paths).validate(delete, Map.of("src/A", "one\ntwo\n"), Set.of("src/A")));
    }

    @Test void validatesMultipleHunksWithNewLineOffsetsAndExactContext() {
        String patch = "--- a/src/A\n+++ b/src/A\n@@ -1 +1,2 @@\n-one\n+first\n+extra\n"
                + "@@ -3 +4 @@\n-three\n+third\n";
        assertEquals(List.of("src/A"), new UnifiedDiffValidator(paths).validate(patch,
                Map.of("src/A", "one\ntwo\nthree\n"), Set.of("src/A")));
        assertThrows(AiGatewayException.class, () -> new UnifiedDiffValidator(paths).validate(
                patch.replace("@@ -3 +4 @@", "@@ -3 +3 @@"), Map.of("src/A", "one\ntwo\nthree\n"), Set.of("src/A")));
    }

    @Test void rejectsUnseenFilesStaleContextCountsRenamesAndMetadata() {
        Map<String, String> original = Map.of("src/A", "old\n");
        for (String patch : List.of(replace("src/B"), replace("src/A").replace("-old", "-stale"),
                replace("src/A").replace("@@ -1 +1 @@", "@@ -1,2 +1 @@"),
                replace("src/A").replace("+++ b/src/A", "+++ b/src/B"),
                "GIT binary patch\n", "diff --git a/src/A b/src/A\nold mode 100644\nnew mode 100755\n",
                replace("src/A") + replace("src/A"), replace("src/A").replace("--- a/", "--- /"))) {
            assertThrows(AiGatewayException.class, () -> new UnifiedDiffValidator(paths).validate(
                    patch, original, Set.of("src/A", "src/B")), patch);
        }
    }

    @Test void declaredTargetsRevisionAndVisibilityMustAgree() {
        assertNotNull(validate(proposal(replace("src/A"), List.of("src/A"), "A"),
                Map.of("src/A", "old\n"), Set.of(), Set.of("src/A")));
        for (CodingProposal invalid : List.of(proposal(replace("src/A"), List.of("src/B"), "A"),
                proposal(replace("src/A"), List.of("src/A", "src/A"), "A"),
                proposal(replace("src/A"), List.of("src/A"), "B"))) {
            assertThrows(AiGatewayException.class, () -> validate(invalid,
                    Map.of("src/A", "old\n"), Set.of(), Set.of("src/A")));
        }
        assertThrows(AiGatewayException.class, () -> validate(proposal(replace("src/A"), List.of("src/A"), "A"),
                Map.of(), Set.of(), Set.of("src/A")));
        assertThrows(AiGatewayException.class, () -> validate(proposal(replace("src/A"), List.of("src/A"), "A"),
                Map.of("src/A", "old\n"), Set.of("src/A"), Set.of("src/A")));
    }

    @Test void verificationMustReferenceEveryUnchangedCriterion() {
        for (List<Integer> indexes : List.of(List.of(1), List.of(1, 3), List.of(1, 1, 2))) {
            CodingProposal proposal = new CodingProposal("A", "change", List.of("src/A"), replace("src/A"),
                    List.of(new CodingProposal.VerificationAction(CodingProposal.Action.TEST, "", indexes)));
            assertThrows(AiGatewayException.class, () -> validate(proposal,
                    Map.of("src/A", "old\n"), Set.of(), Set.of("src/A")));
        }
        assertEquals(List.of(VerificationCriterion.unsupported("criterion one"),
                VerificationCriterion.unsupported("criterion two")), step.verificationCriteria());
    }

    @Test void recognizedCredentialsInPatchAreRejected() {
        String patch = replace("src/A").replace("+new", "+password: CANARY-DB-PASSWORD-0001");
        assertThrows(AiGatewayException.class, () -> validate(proposal(patch, List.of("src/A"), "A"),
                Map.of("src/A", "old\n"), Set.of(), Set.of("src/A")));
    }

    @Test void enforcesEndOfFileMarkersAndRejectsNoOpPatches() {
        String patch = replace("src/A");
        assertThrows(AiGatewayException.class, () -> new UnifiedDiffValidator(paths).validate(
                patch, Map.of("src/A", "old"), Set.of("src/A")));
        String marked = patch.replace("-old\n", "-old\n\\ No newline at end of file\n");
        assertEquals(List.of("src/A"), new UnifiedDiffValidator(paths).validate(
                marked, Map.of("src/A", "old"), Set.of("src/A")));
        assertThrows(AiGatewayException.class, () -> new UnifiedDiffValidator(paths).validate(
                patch.replace("+new", "+old"), Map.of("src/A", "old\n"), Set.of("src/A")));
        assertEquals(List.of("src/A"), new UnifiedDiffValidator(paths).validate(
                patch.replace("-old\n", "-old\r\n").replace("+new\n", "+new\r\n"),
                Map.of("src/A", "old\r\n"), Set.of("src/A")));
    }

    @Test void responseParserRejectsMixedMissingUnknownAndDuplicateFields() {
        CodingProposalParser parser = new CodingProposalParser(new ObjectMapper(), LIMITS);
        String more = "{\"type\":\"NEED_MORE_CONTEXT\",\"expectedRevision\":\"A\","
                + "\"missingDependency\":\"specific implementation needed\",\"pathPrefix\":\"src/service\"}";
        assertInstanceOf(CodingResponse.NeedMore.class, parser.parse(more));
        for (String bad : List.of(more + " {}", more.replace("\"pathPrefix\":\"src/service\"", "\"pathPrefix\":1"),
                more.replace("\"type\":\"NEED_MORE_CONTEXT\"", "\"type\":\"NEED_MORE_CONTEXT\",\"type\":\"NEED_MORE_CONTEXT\""),
                more.replace("\"pathPrefix\":\"src/service\"", "\"pathPrefix\":\"src/service\",\"unifiedDiff\":\"x\""))) {
            assertThrows(AiGatewayException.class, () -> parser.parse(bad));
        }
    }

    @Test void responseParserRejectsArbitraryCommandsAndInvalidCriterionShapes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CodingProposalParser parser = new CodingProposalParser(mapper, LIMITS);
        Map<String, Object> root = new LinkedHashMap<>(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", "A",
                "summary", "change", "declaredAffectedPaths", List.of("src/A"), "unifiedDiff", replace("src/A")));
        for (Map<String, Object> check : List.of(Map.<String, Object>of("action", "SHELL", "target", "", "criterionIndexes", List.of(1)),
                Map.<String, Object>of("action", "TEST", "target", "mvn test", "criterionIndexes", List.of(1)),
                Map.<String, Object>of("action", "FOCUSED_TEST", "target", "Suite;rm", "criterionIndexes", List.of(1)),
                Map.<String, Object>of("action", "FOCUSED_TEST", "target", "", "criterionIndexes", List.of(1)),
                Map.<String, Object>of("action", "TEST", "target", "", "criterionIndexes", List.of(1.2)),
                Map.<String, Object>of("action", "TEST", "target", "", "criterionIndexes", List.of("1")))) {
            root.put("verificationStrategy", List.of(check));
            assertThrows(AiGatewayException.class, () -> parser.parse(mapper.writeValueAsString(root)));
        }
    }
}
