package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenJdkSelectorTest {
    @Test
    void standardReleaseAndExplicitOverrideUseControlledImages() {
        var selector = new MavenJdkSelector();
        var poms = Map.of("pom.xml", DockerAgentSandboxTest.pom(17).getBytes(StandardCharsets.UTF_8));
        assertEquals(17, selector.select(poms, null, DockerAgentSandboxTest.properties(Path.of("data/unused"))));
        assertEquals(21, selector.select(poms, 21, DockerAgentSandboxTest.properties(Path.of("data/unused"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {18, 19, 20})
    void automaticSelectionFallsBackFromDefault17ToConfigured21(int languageLevel) {
        var defaults = DockerAgentSandboxTest.properties(Path.of("data/unused"));
        var properties = new AgentSandboxProperties(defaults.root(), defaults.docker(), defaults.images(), 17,
                defaults.owner(), defaults.cacheVolume(), defaults.memory(), defaults.cpus(), defaults.pids());
        var poms = Map.of("pom.xml", DockerAgentSandboxTest.pom(languageLevel).getBytes(StandardCharsets.UTF_8));
        var layout = com.ayywl.delveforge.infrastructure.workspace.MavenRepositoryLayout.inspect(poms);
        var selector = new MavenJdkSelector();

        assertEquals(21, layout.recommendedJdk());
        assertEquals(21, selector.select(poms, null, properties));
        assertEquals("JDK_UNSUPPORTED", assertThrows(AgentException.class,
                () -> selector.select(poms, 17, properties)).code());
    }

    @Test
    void automaticSelectionUsesAvailable21WhenRecommended17ImageIsAbsent() {
        var defaults = DockerAgentSandboxTest.properties(Path.of("data/unused"));
        var properties = new AgentSandboxProperties(defaults.root(), defaults.docker(),
                Map.of(21, defaults.images().get(21)), 21, defaults.owner(), defaults.cacheVolume(),
                defaults.memory(), defaults.cpus(), defaults.pids());
        var poms = Map.of("pom.xml", DockerAgentSandboxTest.pom(17).getBytes(StandardCharsets.UTF_8));

        assertEquals(21, new MavenJdkSelector().select(poms, null, properties));
    }

    @Test
    void automaticSelectionRejectsWhenNoConfiguredImageMeetsRequirements() {
        var defaults = DockerAgentSandboxTest.properties(Path.of("data/unused"));
        var properties = new AgentSandboxProperties(defaults.root(), defaults.docker(),
                Map.of(17, defaults.images().get(17)), 17, defaults.owner(), defaults.cacheVolume(),
                defaults.memory(), defaults.cpus(), defaults.pids());
        var poms = Map.of("pom.xml", DockerAgentSandboxTest.pom(18).getBytes(StandardCharsets.UTF_8));

        assertEquals("JDK_UNSUPPORTED", assertThrows(AgentException.class,
                () -> new MavenJdkSelector().select(poms, null, properties)).code());
    }

    @Test
    void unsupportedAndAmbiguousRequirementsAreEnvironmentDiagnostics() {
        var selector = new MavenJdkSelector();
        var properties = DockerAgentSandboxTest.properties(Path.of("data/unused"));
        assertEquals("JDK_UNSUPPORTED", assertThrows(AgentException.class, () -> selector.select(Map.of(
                "pom.xml", DockerAgentSandboxTest.pom(25).getBytes(StandardCharsets.UTF_8)), null, properties)).code());
        var mixed = Map.of(
                "pom.xml", DockerAgentSandboxTest.pom(17).replace("</project>",
                        "<modules><module>child</module></modules></project>").getBytes(StandardCharsets.UTF_8),
                "child/pom.xml", DockerAgentSandboxTest.pom(21).getBytes(StandardCharsets.UTF_8));
        assertEquals(21, selector.select(mixed, null, properties));
        assertEquals("JDK_UNSUPPORTED", assertThrows(AgentException.class,
                () -> selector.select(mixed, 17, properties)).code());
    }

    @Test
    void moduleMetadataBudgetCountsUtf8BytesBeforeWindowsEnvironmentTransport() {
        Map<String, byte[]> poms = new java.util.HashMap<>();
        StringBuilder modules = new StringBuilder();
        for (int i = 0; i < 127; i++) {
            String name = "模块".repeat(15) + i;
            modules.append("<module>").append(name).append("</module>");
            poms.put(name + "/pom.xml", "<project/>".getBytes(StandardCharsets.UTF_8));
        }
        poms.put("pom.xml", ("<project><modules>" + modules + "</modules></project>").getBytes(StandardCharsets.UTF_8));
        var failure = assertThrows(com.ayywl.delveforge.infrastructure.workspace.MavenRepositoryLayout.Unsupported.class,
                () -> com.ayywl.delveforge.infrastructure.workspace.MavenRepositoryLayout.inspect(poms));
        assertEquals("MAVEN_MODULE_LIMIT", failure.code());
    }

    @Test
    void externalEntityPomsAreRejectedWithoutResolution() {
        assertEquals("MAVEN_CONFIGURATION", assertThrows(AgentException.class, () -> new MavenJdkSelector().select(
                Map.of("pom.xml", "<!DOCTYPE x [<!ENTITY xx SYSTEM 'file:///host'>]><project>&xx;</project>"
                        .getBytes(StandardCharsets.UTF_8)), null, DockerAgentSandboxTest.properties(Path.of("data/unused")))).code());
    }

    @Test
    void compilerLevelEightUsesModernJdkAndUnrelatedInvalidFixturePomsAreNotEligibilityGates() {
        var properties = DockerAgentSandboxTest.properties(Path.of("data/unused"));
        var poms = Map.of("pom.xml", DockerAgentSandboxTest.pom(8).getBytes(StandardCharsets.UTF_8),
                "src/test/resources/invalid/pom.xml", "intentionally invalid test fixture".getBytes(StandardCharsets.UTF_8));
        assertEquals(21, new MavenJdkSelector().select(poms, null, properties));
        assertEquals(17, new MavenJdkSelector().select(poms, 17, properties));
    }
}
