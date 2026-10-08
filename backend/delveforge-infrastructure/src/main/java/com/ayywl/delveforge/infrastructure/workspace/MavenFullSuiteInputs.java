package com.ayywl.delveforge.infrastructure.workspace;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** 仅认证当前支持的 Maven / Surefire 全套输入；未知配置拒绝，不修改仓库配置。 */
final class MavenFullSuiteInputs {
    static final String EFFECTIVE_POM = ".git/delveforge-effective-pom.xml";
    static final String EFFECTIVE_POM_GOAL = "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom";
    private static final Set<String> SAFE_MAVEN_ARGUMENTS = Set.of("-B", "--batch-mode", "-ntp", "--no-transfer-progress",
            "-o", "--offline", "-e", "--errors", "-q", "--quiet", "-V", "--show-version");
    private static final Set<String> SELECTION_PROPERTIES = Set.of("test", "it.test", "groups", "excludedGroups",
            "surefire.includes", "surefire.excludes", "surefire.includesFile", "surefire.excludesFile",
            "surefire.suiteXmlFiles", "surefire.includeJUnit5Engines", "surefire.excludeJUnit5Engines");
    private static final Set<String> SKIP_PROPERTIES = Set.of("skipTests", "maven.test.skip", "maven.test.skip.exec");
    private static final Set<String> SAFE_SUREFIRE_OPTIONS = Set.of("failIfNoTests", "failIfNoSpecifiedTests",
            "trimStackTrace", "useModulePath", "forkCount", "reuseForks", "redirectTestOutputToFile");

    private MavenFullSuiteInputs() {}

    static boolean localConfigurationSupported(Path run, int maxBytes) {
        try {
            // Maven 参数可改变 reactor、settings、profile 或测试选择；不解释未知参数的含义。
            String maven = text(run, ".mvn/maven.config", maxBytes);
            for (String line : maven.lines().toList()) {
                if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
                    for (String argument : line.strip().split("\\s+")) {
                        if (!SAFE_MAVEN_ARGUMENTS.contains(argument)) {
                            return false;
                        }
                    }
                }
            }
            String jvm = text(run, ".mvn/jvm.config", maxBytes);
            for (String argument : jvm.strip().split("\\s+")) {
                if (!argument.isEmpty() && !argument.matches("-Xm[sx][1-9][0-9]*[kKmMgG]?")) {
                    return false;
                }
            }
            return !Files.exists(run.resolve(".mvn/extensions.xml"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            return false;
        }
    }

    static boolean effectivePomSupported(Path run, int maxBytes) {
        try {
            String xml = text(run, EFFECTIVE_POM, maxBytes);
            if (xml.isBlank()) {
                return false;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException {
                    throw failure;
                }
            });
            Element root = parser.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))).getDocumentElement();
            List<Element> projects = root.getTagName().equals("project") ? List.of(root)
                    : root.getTagName().equals("projects") ? children(root, "project") : List.of();
            return !projects.isEmpty() && projects.size() <= 100
                    && projects.stream().allMatch(project -> projectSupported(project, run));
        } catch (IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException failure) {
            return false;
        }
    }

    private static boolean projectSupported(Element project, Path run) {
        if (value(project, "groupId").isEmpty() || value(project, "artifactId").isEmpty()
                || value(project, "version").isEmpty()) {
            return false;
        }
        Element properties = child(project, "properties");
        for (Element property : children(properties, null)) {
            String name = property.getTagName();
            String value = property.getTextContent().strip();
            if ((SELECTION_PROPERTIES.contains(name) && !value.isEmpty())
                    || (SKIP_PROPERTIES.contains(name) && !value.equals("false"))) {
                return false;
            }
        }
        Element build = child(project, "build");
        if (build == null || !children(child(build, "extensions"), null).isEmpty()) {
            return false;
        }
        String testSource = value(build, "testSourceDirectory");
        if (testSource.isEmpty()) {
            return false;
        }
        try {
            Path path = Path.of(testSource).toAbsolutePath().normalize();
            if (!path.startsWith(run.toAbsolutePath().normalize()) || !path.endsWith(Path.of("src", "test", "java"))) {
                return false;
            }
        } catch (InvalidPathException failure) {
            return false;
        }
        for (Element plugin : children(child(build, "plugins"), "plugin")) {
            String artifact = value(plugin, "artifactId");
            if (value(plugin, "extensions").equals("true") || artifact.equals("maven-failsafe-plugin")) {
                return false;
            }
            if (artifact.equals("maven-surefire-plugin")) {
                String group = value(plugin, "groupId");
                if ((!group.isEmpty() && !group.equals("org.apache.maven.plugins"))
                        || !surefireConfigurationSupported(child(plugin, "configuration"))
                        || !children(child(plugin, "dependencies"), null).isEmpty()) {
                    return false;
                }
                for (Element execution : children(child(plugin, "executions"), "execution")) {
                    if (!value(execution, "phase").equals("test")
                            || !children(child(execution, "goals"), "goal").stream()
                                .allMatch(goal -> goal.getTextContent().equals("test"))
                            || children(child(execution, "goals"), "goal").isEmpty()
                            || !surefireConfigurationSupported(child(execution, "configuration"))) {
                        return false;
                    }
                }
            } else if (artifact.equals("maven-compiler-plugin")) {
                List<Element> configurations = new ArrayList<>(children(plugin, "configuration"));
                children(child(plugin, "executions"), "execution").forEach(execution ->
                        configurations.addAll(children(execution, "configuration")));
                for (Element configuration : configurations) {
                    if (child(configuration, "testIncludes") != null || child(configuration, "testExcludes") != null
                            || (!value(configuration, "skip").isEmpty() && !value(configuration, "skip").equals("false"))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean surefireConfigurationSupported(Element configuration) {
        for (Element option : children(configuration, null)) {
            String name = option.getTagName();
            String value = option.getTextContent().strip();
            if (Set.of("skip", "skipTests", "skipExec", "testFailureIgnore").contains(name)) {
                if (!value.equals("false")) {
                    return false;
                }
            } else if (!SAFE_SUREFIRE_OPTIONS.contains(name) || !children(option, null).isEmpty()
                    || !(value.equals("true") || value.equals("false") || value.matches("[1-9][0-9]*"))) {
                // 包括 test / includes / excludes / groups / engine / suiteXmlFiles / argLine 等。
                return false;
            }
        }
        return true;
    }

    private static String text(Path run, String relative, int maxBytes) throws IOException {
        Path path = run.resolve(relative);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(run.toRealPath())
                || Files.size(path) > maxBytes) {
            throw new IOException("Full-suite input exceeds its location or byte budget");
        }
        return Files.readString(path);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && (name == null || element.getTagName().equals(name))) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }

    private static String value(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().strip();
    }
}
