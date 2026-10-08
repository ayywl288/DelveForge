package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MavenVerificationCommandTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void fullCommandPlacesLauncherFirstAndTestFlagsAfterItOnBothPlatforms(boolean windows) {
        for (CodingProposal.Action action : List.of(CodingProposal.Action.BUILD, CodingProposal.Action.TEST,
                CodingProposal.Action.FOCUSED_TEST)) {
            List<String> expected = new ArrayList<>(windows ? List.of("cmd.exe", "/d", "/c", "mvnw.cmd")
                    : List.of("sh", "./mvnw"));
            expected.addAll(List.of("--batch-mode", "--no-transfer-progress", "-o"));
            if (action == CodingProposal.Action.BUILD) {
                expected.addAll(List.of("-DskipTests", "package"));
            } else {
                expected.addAll(List.of("-DskipTests=false", "-Dmaven.test.skip=false", "-DfailIfNoTests=true"));
                if (action == CodingProposal.Action.FOCUSED_TEST) {
                    expected.addAll(List.of("-Dtest=ScheduleTest#schedule", "-Dsurefire.failIfNoSpecifiedTests=true"));
                } else {
                    expected.addAll(List.of("-Doutput=" + MavenFullSuiteInputs.EFFECTIVE_POM,
                            MavenFullSuiteInputs.EFFECTIVE_POM_GOAL));
                }
                expected.add("test");
            }
            assertEquals(expected, GitWorkspaceVerificationAdapter.mavenCommand(
                    new CodingProposal.VerificationAction(action, "ScheduleTest#schedule", List.of(1)), windows));
        }
    }
}
