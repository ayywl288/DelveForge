package com.ayywl.delveforge.infrastructure.workspace;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MavenFullSuiteInputsTest {
    @TempDir Path run;

    private void write(String path, String content) throws Exception {
        Files.createDirectories(run.resolve(path).getParent());
        Files.writeString(run.resolve(path), content);
    }

    private String pom(String configuration) {
        return "<project><groupId>example</groupId><artifactId>report</artifactId><version>1</version><build>"
                + "<testSourceDirectory>" + run.resolve("src/test/java").toAbsolutePath().toString().replace("&", "&amp;")
                + "</testSourceDirectory><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                + "<executions><execution><id>default-test</id><phase>test</phase><goals><goal>test</goal></goals>"
                + "</execution></executions><configuration>" + configuration + "</configuration></plugin></plugins></build></project>";
    }

    @Test void supportedOrdinaryInputsAndDefaultSuiteAreAccepted() throws Exception {
        write(".mvn/maven.config", "# ordinary inputs\n-B\n--no-transfer-progress\n");
        write(".mvn/jvm.config", "-Xms128m -Xmx512m\n");
        write(MavenFullSuiteInputs.EFFECTIVE_POM, pom("<skipTests>false</skipTests><trimStackTrace>true</trimStackTrace>"));
        assertTrue(MavenFullSuiteInputs.localConfigurationSupported(run, 4096));
        assertTrue(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
    }

    @ParameterizedTest @ValueSource(strings = {"-Dtest=PassingTest", "-Dgroups=fast", "-Dsurefire.excludesFile=omit.txt",
            "--projects=only", "-rf:only", "-fother.xml", "-Pfast", "-sother-settings.xml", "-Dunknown=true"})
    void selectionAndUninterpretedMavenArgumentsCannotEstablishFullSuite(String argument) throws Exception {
        write(".mvn/maven.config", argument);
        assertFalse(MavenFullSuiteInputs.localConfigurationSupported(run, 4096));
    }

    @Test void jvmSelectorsExtensionsAndInputBudgetsFailClosed() throws Exception {
        write(".mvn/jvm.config", "-Dtest=PassingTest");
        assertFalse(MavenFullSuiteInputs.localConfigurationSupported(run, 4096));
        Files.delete(run.resolve(".mvn/jvm.config"));
        write(".mvn/extensions.xml", "<extensions/>");
        assertFalse(MavenFullSuiteInputs.localConfigurationSupported(run, 4096));
        Files.delete(run.resolve(".mvn/extensions.xml"));
        write(".mvn/maven.config", "-B\n".repeat(300));
        assertFalse(MavenFullSuiteInputs.localConfigurationSupported(run, 100));
    }

    @ParameterizedTest @ValueSource(strings = {"<test>PassingTest</test>", "<includes><include>**/PassingTest.java</include></includes>",
            "<excludes><exclude>**/FailingTest.java</exclude></excludes>", "<includesFile>only.txt</includesFile>",
            "<excludesFile>omit.txt</excludesFile>", "<groups>fast</groups>", "<excludedGroups>slow</excludedGroups>",
            "<suiteXmlFiles><suiteXmlFile>subset.xml</suiteXmlFile></suiteXmlFiles>",
            "<includeJUnit5Engines><engine>selected</engine></includeJUnit5Engines>",
            "<excludeJUnit5Engines><engine>omitted</engine></excludeJUnit5Engines>",
            "<skipTests>true</skipTests>", "<testFailureIgnore>true</testFailureIgnore>", "<unknown>value</unknown>"})
    void inheritedAndProfileResolvedSurefireSelectorsAndUnknownSettingsAreRejected(String configuration) throws Exception {
        write(MavenFullSuiteInputs.EFFECTIVE_POM, pom(configuration));
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
    }

    @Test void everyReactorProjectAndExecutionMustRetainDefaultSuiteSemantics() throws Exception {
        write(MavenFullSuiteInputs.EFFECTIVE_POM, "<projects>" + pom("") + pom("<test>PassingTest</test>") + "</projects>");
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
        write(MavenFullSuiteInputs.EFFECTIVE_POM, pom("").replace("<phase>test</phase>", "<phase>none</phase>"));
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
        write(MavenFullSuiteInputs.EFFECTIVE_POM, pom("").replace("<build>",
                "<properties><test>PassingTest</test></properties><build>"));
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
        write(MavenFullSuiteInputs.EFFECTIVE_POM, pom("<testExcludes><testExclude>FailingTest.java</testExclude></testExcludes>")
                .replace("maven-surefire-plugin", "maven-compiler-plugin"));
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
    }

    @Test void absentMalformedExternalXmlAndOversizeEffectiveEvidenceCannotProveAnything() throws Exception {
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
        write(MavenFullSuiteInputs.EFFECTIVE_POM, "<project>");
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
        write(MavenFullSuiteInputs.EFFECTIVE_POM, "<project/>");
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
        write(MavenFullSuiteInputs.EFFECTIVE_POM, "<!DOCTYPE project [<!ENTITY secret SYSTEM 'file:///not-read'>]><project>&secret;</project>");
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 4096));
        write(MavenFullSuiteInputs.EFFECTIVE_POM, pom(""));
        assertFalse(MavenFullSuiteInputs.effectivePomSupported(run, 8));
    }
}
