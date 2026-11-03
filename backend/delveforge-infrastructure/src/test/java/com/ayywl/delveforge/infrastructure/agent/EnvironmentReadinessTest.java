package com.ayywl.delveforge.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvironmentReadinessTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode check(String status, String output) {
        return json.createObjectNode().put("classification", status).put("output", output);
    }

    @Test
    void declarationsAndZeroExitWithoutAProjectPhaseDoNotProveReadiness() {
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("BUILD_PASSED_NOT_TESTED", "echo done")));
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("TEST_EXECUTION_UNVERIFIED", "")));
        assertEquals("READY", EnvironmentReadiness.classify(check("BUILD_PASSED_NOT_TESTED", "")
                .put("checkType", "FRONTEND").put("checkExecutions", 1)));
    }

    @Test
    void actualCompilerAndTestsCanFailWithoutFailingEnvironmentReadiness() {
        assertEquals("READY_BASELINE_FAILURE", EnvironmentReadiness.classify(check("COMPILATION_FAILED",
                "[INFO] --- compiler:3.14.1:compile (default-compile) @ example ---\n[ERROR] COMPILATION ERROR")));
        assertEquals("READY_BASELINE_FAILURE", EnvironmentReadiness.classify(check("TESTS_FAILED", "assertion failed")
                .put("reports", 1).put("executedTests", 1)));
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("TESTS_FAILED", "ECONNREFUSED database")
                .put("reports", 1).put("executedTests", 1)));
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("COMPILATION_FAILED", "ambiguous failure")));
    }

    @Test
    void externalJavaPrerequisitesBlockFailedTestsButExpectedDiagnosticsDoNotOverridePassingReports() {
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("TESTS_FAILED",
                "java.net.ConnectException: Connection refused").put("reports", 1).put("executedTests", 1)));
        assertEquals("READY", EnvironmentReadiness.classify(check("TESTS_PASSED",
                "expected java.net.ConnectException: Connection refused").put("reports", 1).put("executedTests", 1)));
    }

    @Test
    void missingDependenciesAndUnknownFailuresRemainBlocked() {
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("FRONTEND_BUILD_FAILED",
                "Cannot find package 'vite'").put("checkType", "FRONTEND").put("checkExecutions", 1)));
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("FRONTEND_BUILD_FAILED", "exit 1")
                .put("checkType", "FRONTEND").put("checkExecutions", 1)));
        assertEquals("ENVIRONMENT_BLOCKED", EnvironmentReadiness.classify(check("DEPENDENCY_MISSING", "offline mode")));
    }

    @Test
    void missingMavenRecoveryUsesBoundedArtifactIdentities() {
        assertEquals(java.util.Set.of("org.junit.platform:junit-platform-launcher:1.11.4:jar"),
                DockerAgentSandbox.missingMavenArtifacts("The following artifacts could not be resolved: "
                        + "org.junit.platform:junit-platform-launcher:jar:1.11.4 (absent): offline mode"));
        assertEquals(java.util.Set.of(), DockerAgentSandbox.missingMavenArtifacts("https://evil.invalid/archive.tgz"));
        assertEquals(java.util.Set.of("org.springframework.boot:spring-boot-starter-webmvc-test:4.0.9:jar"),
                DockerAgentSandbox.missingMavenArtifacts("Could not resolve dependencies for project "
                        + "com.example:rest-service-complete:jar:0.0.1-SNAPSHOT: The following artifacts could not "
                        + "be resolved: org.springframework.boot:spring-boot-starter-webmvc-test:jar:4.0.9 (absent): "
                        + "Cannot access delveforge-central in offline mode"));
    }
}
