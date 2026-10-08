package com.ayywl.delveforge.infrastructure.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FocusedTestEvidenceTest {
    @TempDir Path run;

    @Test void evidenceMustIdentifyTheActualExecutedClassAndMethod() throws Exception {
        Path reports = run.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-Unrelated.xml"), """
            <testsuite tests="3" skipped="1" failures="0" errors="0">
              <testcase classname="example.UnrelatedTest" name="value"/>
              <testcase classname="example.ScheduleTest" name="schedule"><skipped/></testcase>
              <testcase classname="example.ScheduleTest" name="other"/>
            </testsuite>
            """);
        var adapter = new GitWorkspaceVerificationAdapter(null, null,
                new VerificationProperties(java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(180), 1048576, 16));
        var evidence = adapter.testEvidence(run, 0);
        assertTrue(evidence.includes("UnrelatedTest#value"));
        assertTrue(evidence.includes("example.UnrelatedTest"));
        assertFalse(evidence.includes("ScheduleTest#schedule"));
        assertFalse(evidence.includes("other.UnrelatedTest#value"));
        assertFalse(evidence.includes("MissingTest"));
    }

    @ParameterizedTest @ValueSource(strings = {"value(int)[1]", "value(int)[2]", "value(java.lang.String, int[])[3]", "value()"})
    void supportedSignatureAndInvocationSuffixKeepExactLogicalIdentity(String reported) {
        var evidence = new GitWorkspaceVerificationAdapter.TestEvidence(1, 0, Set.of("example.ReportTest#" + reported));
        assertTrue(evidence.includes("ReportTest#value"));
        assertTrue(evidence.includes("example.ReportTest#value"));
        assertFalse(evidence.includes("ReportTest#valueExtra"));
        assertFalse(evidence.includes("OtherTest#value"));
    }

    @ParameterizedTest @ValueSource(strings = {"valueExtra(int)[1]", "value(int)[0]", "value(int)[1]garbage", "value(text!)", "value[int]", "value(int)[1][2]"})
    void malformedOrOtherMethodNamesCannotSupportTheRequestedMethod(String reported) {
        var evidence = new GitWorkspaceVerificationAdapter.TestEvidence(1, 0, Set.of("example.ReportTest#" + reported));
        assertFalse(evidence.includes("ReportTest#value"));
    }

    @Test void noExecutionFailuresAndSkippedReportsCannotProvideSuccessEvidence() throws Exception {
        assertFalse(new GitWorkspaceVerificationAdapter.TestEvidence(0, 0, Set.of("ReportTest#value(int)[1]")).includes("ReportTest#value"));
        assertFalse(new GitWorkspaceVerificationAdapter.TestEvidence(2, 1, Set.of("ReportTest#value(int)[1]")).includes("ReportTest#value"));
        Path reports = run.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-Report.xml"), """
            <testsuite tests="2" skipped="1" failures="1" errors="0">
              <testcase classname="ReportTest" name="value(int)[1]"><skipped/></testcase>
              <testcase classname="ReportTest" name="value(int)[2]"><failure/></testcase>
            </testsuite>
            """);
        var adapter = new GitWorkspaceVerificationAdapter(null, null,
                new VerificationProperties(java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(180), 1048576, 16));
        assertFalse(adapter.testEvidence(run, 0).includes("ReportTest#value"));
    }
}
