package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** 仅做保守环境推断；profiles、父 POM 与 toolchain 歧义要求显式选择，不改 POM。 */
final class MavenJdkSelector {
    int select(Map<String, byte[]> poms, Integer configured, AgentSandboxProperties properties) {
        if (configured != null) {
            return requireImage(configured, properties);
        }
        Deque<String> pending = new ArrayDeque<>();
        if (poms.containsKey("pom.xml")) {
            pending.add("pom.xml");
        } else if (poms.size() == 1) {
            pending.add(poms.keySet().iterator().next());
        } else {
            throw new AgentException("JDK_AMBIGUOUS", "Set targetJdk explicitly for repositories with multiple Maven roots");
        }
        Set<String> visited = new HashSet<>();
        Set<Integer> versions = new HashSet<>();
        boolean unresolved = false;
        while (!pending.isEmpty()) {
            String pom = pending.removeFirst();
            if (!visited.add(pom)) {
                continue;
            }
            byte[] bytes = poms.get(pom);
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
                Map<String, String> values = new HashMap<>();
                NodeList containers = root.getElementsByTagName("properties");
                for (int i = 0; i < containers.getLength(); i++) {
                    NodeList children = containers.item(i).getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        if (children.item(j) instanceof Element property) {
                            values.put(property.getTagName(), property.getTextContent().trim());
                        }
                    }
                }
                for (String tag : new String[] {"maven.compiler.release", "maven.compiler.source", "maven.compiler.target",
                        "java.version", "release", "source", "target", "jdk"}) {
                    NodeList nodes = root.getElementsByTagName(tag);
                    for (int i = 0; i < nodes.getLength(); i++) {
                        String value = nodes.item(i).getTextContent().trim();
                        for (int depth = 0; depth < 4 && value.matches("\\$\\{[^}]+}"); depth++) {
                            value = values.getOrDefault(value.substring(2, value.length() - 1), value);
                        }
                        if (value.matches("(1\\.)?[0-9]{1,2}")) {
                            versions.add(Integer.parseInt(value.replaceFirst("^1\\.", "")));
                        } else if (value.contains("${")) {
                            unresolved = true;
                        }
                    }
                }
                // 只跟随声明的 reactor modules，不把 src/test/resources 内的坏 POM 夹具当作构建环境。
                for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!(child instanceof Element modules) || !modules.getTagName().equals("modules")) {
                        continue;
                    }
                    NodeList names = modules.getElementsByTagName("module");
                    for (int i = 0; i < names.getLength(); i++) {
                        Path parent = Path.of(pom).getParent();
                        String module = (parent == null ? Path.of("") : parent)
                                .resolve(names.item(i).getTextContent().trim()).resolve("pom.xml")
                                .normalize().toString().replace('\\', '/');
                        if (poms.containsKey(module)) {
                            pending.addLast(module);
                        } else {
                            unresolved = true;
                        }
                    }
                }
            } catch (Exception failure) {
                throw new AgentException("MAVEN_CONFIGURATION", "Cannot safely inspect Maven JDK configuration", failure);
            }
        }
        if (versions.size() > 1 || unresolved) {
            throw new AgentException("JDK_AMBIGUOUS", "Set targetJdk explicitly: Maven configurations name multiple JDKs");
        }
        int chosen = versions.stream().findFirst().orElse(properties.defaultJdk());
        if (!properties.images().containsKey(chosen) && chosen >= 8 && chosen <= 21) {
            // compiler release/source/target 是语言级别，不等同于所需的构建 JVM。
            int level = chosen;
            if ((properties.defaultJdk() == 17 || properties.defaultJdk() == 21) && properties.defaultJdk() >= level) {
                chosen = properties.defaultJdk();
            } else {
                chosen = properties.images().keySet().stream().filter(jdk -> (jdk == 17 || jdk == 21) && jdk >= level)
                        .min(Integer::compareTo).orElse(chosen);
            }
        }
        return requireImage(chosen, properties);
    }

    private int requireImage(int chosen, AgentSandboxProperties properties) {
        if (!properties.images().containsKey(chosen)) {
            throw new AgentException("JDK_UNSUPPORTED", "Configure a controlled image for target JDK " + chosen
                    + "; supported configured JDKs: " + properties.images().keySet());
        }
        return chosen;
    }
}
