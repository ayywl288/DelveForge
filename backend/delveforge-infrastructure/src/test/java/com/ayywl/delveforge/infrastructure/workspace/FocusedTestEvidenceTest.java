package com.ayywl.delveforge.infrastructure.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

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
}
