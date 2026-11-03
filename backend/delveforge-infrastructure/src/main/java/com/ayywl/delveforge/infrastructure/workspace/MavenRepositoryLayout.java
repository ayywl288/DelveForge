package com.ayywl.delveforge.infrastructure.workspace;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** 仅解析 Git 中的 Maven 描述，不执行 effective-pom、Wrapper、插件或项目代码。 */
public record MavenRepositoryLayout(String directory, List<String> modules, int languageLevel, int minimumBuildJdk,
        int recommendedJdk) {
    public static final class Unsupported extends RuntimeException {
        private final String code;

        public Unsupported(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static MavenRepositoryLayout inspect(Map<String, byte[]> inputs) {
        return inspect(inputs, null);
    }

    /** 仅发现入口；profile 的激活、生效模型和插件执行交给实际 Maven。 */
    public static List<String> rootDirectories(Map<String, byte[]> inputs) {
        List<String> names = inputs.keySet().stream()
                .filter(path -> !path.startsWith("src/") && !path.contains("/src/")).sorted().toList();
        if (names.size() > 128 || names.stream().mapToInt(name -> name.getBytes(StandardCharsets.UTF_8).length).sum() > 8192) {
            throw new Unsupported("MAVEN_MODULE_LIMIT", "Maven 描述超过 128 个或 8 KiB 路径元数据预算。");
        }
        Set<String> modules = new HashSet<>();
        for (String name : names) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var parser = factory.newDocumentBuilder();
                parser.setErrorHandler(new DefaultHandler());
                Element pom = parser.parse(new ByteArrayInputStream(inputs.get(name))).getDocumentElement();
                var declarations = pom.getElementsByTagName("module");
                for (int i = 0; i < declarations.getLength(); i++) {
                    String relative = declarations.item(i).getTextContent().trim();
                    if (!relative.isEmpty() && !relative.contains("${")) {
                        String child = resolve(name, relative + "/pom.xml");
                        if (names.contains(child)) {
                            modules.add(child);
                        }
                    }
                }
            } catch (Exception invalid) {
                // 无法静态解释的 POM 仍是候选；不能据此拒绝整个安全 Git 仓库。
            }
        }
        List<String> roots = names.stream().filter(name -> !modules.contains(name)).toList();
        return (roots.isEmpty() ? names : roots).stream().map(MavenRepositoryLayout::directory).toList();
    }

    /** 显式入口只影响本次构建选择，不改变仓库身份或 Step 的修改范围。 */
    public static MavenRepositoryLayout inspect(Map<String, byte[]> inputs, String selectedDirectory) {
        Map<String, Element> poms = new HashMap<>();
        for (var entry : inputs.entrySet()) {
            if (entry.getKey().contains("/src/") || entry.getKey().startsWith("src/")) {
                continue;
            }
            try {
                if (poms.size() >= 128) {
                    throw new Unsupported("MAVEN_MODULE_LIMIT", "Maven 描述超过 128 个受控模块的解析预算。");
                }
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setExpandEntityReferences(false);
                var parser = factory.newDocumentBuilder();
                parser.setErrorHandler(new DefaultHandler() {
                    @Override
                    public void fatalError(SAXParseException error) throws SAXException {
                        throw error;
                    }
                });
                Element pom = parser.parse(new ByteArrayInputStream(entry.getValue())).getDocumentElement();
                if (!pom.getTagName().equals("project")) {
                    throw new Unsupported("MAVEN_CONFIGURATION", "POM root must be project");
                }
                poms.put(entry.getKey(), pom);
            } catch (Unsupported failure) {
                throw failure;
            } catch (Exception failure) {
                String selected = selectedDirectory == null ? null
                        : selectedDirectory.equals(".") ? "pom.xml" : selectedDirectory + "/pom.xml";
                if (selected != null && !entry.getKey().equals(selected)) {
                    continue;
                }
                throw new Unsupported("MAVEN_CONFIGURATION", "无法安全解析 Maven 配置，请检查 POM。");
            }
        }
        if (poms.isEmpty()) {
            throw new Unsupported("MAVEN_REQUIRED", "未发现 Maven 构建入口；请选择 Java/Maven 仓库。");
        }
        Set<String> children = new HashSet<>();
        Map<String, List<String>> edges = new HashMap<>();
        for (var entry : poms.entrySet()) {
            List<String> modules = new ArrayList<>();
            for (Element child : children(child(entry.getValue(), "modules"), "module")) {
                String value = child.getTextContent().trim();
                String path = resolve(entry.getKey(), value + "/pom.xml");
                modules.add(path);
                children.add(path);
            }
            edges.put(entry.getKey(), modules);
        }
        List<String> roots = poms.keySet().stream().filter(path -> !children.contains(path)).sorted().toList();
        String selected = selectedDirectory == null ? null
                : selectedDirectory.equals(".") ? "pom.xml" : selectedDirectory + "/pom.xml";
        if (selected == null && roots.size() != 1) {
            throw new Unsupported("MAVEN_ROOT_AMBIGUOUS", "请选择 Maven 入口："
                    + String.join(", ", roots.stream().map(MavenRepositoryLayout::directory).toList()));
        }
        if (selected != null && !poms.containsKey(selected)) {
            throw new Unsupported("MAVEN_REQUIRED", "所选目录没有可读取的 Maven POM。");
        }
        String root = selected == null ? roots.getFirst() : selected;
        List<String> reactor = new ArrayList<>();
        visit(root, edges, new HashSet<>(), new HashSet<>(), reactor);
        if (reactor.stream().mapToInt(path -> path.getBytes(StandardCharsets.UTF_8).length).sum() > 8192) {
            throw new Unsupported("MAVEN_MODULE_LIMIT", "Maven 模块路径超过 8 KiB 受控元数据预算。");
        }
        int level = 0;
        int minimum = 0;
        for (String name : reactor) {
            Element pom = poms.get(name);
            Map<String, String> properties = properties(name, poms, new HashSet<>());
            List<String> versions = new ArrayList<>();
            for (String key : List.of("java.version", "maven.compiler.release", "maven.compiler.source", "maven.compiler.target")) {
                if (properties.containsKey(key)) {
                    versions.add(properties.get(key));
                }
            }
            Element build = child(pom, "build");
            if (child(build, "extensions") != null) {
                throw new Unsupported("MAVEN_EXTENSION_UNSUPPORTED", "项目使用 Maven 构建扩展，需要明确受控环境支持。");
            }
            for (Element plugin : children(child(build, "plugins"), "plugin")) {
                String id = value(plugin, "artifactId");
                if (value(plugin, "extensions").equals("true") || id.equals("maven-toolchains-plugin")) {
                    throw new Unsupported("MAVEN_TOOLCHAIN_UNSUPPORTED", "项目要求特殊 Maven 扩展或 toolchain，需要明确环境支持。");
                }
                if (id.equals("maven-compiler-plugin")) {
                    Element config = child(plugin, "configuration");
                    for (String key : List.of("release", "source", "target")) {
                        if (!value(config, key).isEmpty()) {
                            versions.add(value(config, key));
                        }
                    }
                    if (child(config, "jdkToolchain") != null || child(config, "executable") != null) {
                        throw new Unsupported("MAVEN_TOOLCHAIN_UNSUPPORTED", "编译器要求自定义 JDK，需要明确受控环境支持。");
                    }
                }
            }
            Element parent = child(pom, "parent");
            if (value(parent, "groupId").equals("org.springframework.boot")) {
                String version = value(parent, "version");
                if (version.startsWith("3.") || version.startsWith("4.")) {
                    minimum = Math.max(minimum, 17);
                }
            }
            for (String raw : versions) {
                String resolved = raw;
                for (int i = 0; i < 8 && resolved.matches("\\$\\{[^}]+}"); i++) {
                    resolved = properties.getOrDefault(resolved.substring(2, resolved.length() - 1), resolved);
                }
                if (!resolved.matches("(1\\.)?[0-9]{1,2}")) {
                    throw new Unsupported("JDK_AMBIGUOUS", "无法从 Maven 配置确定 Java 语言级别，需要明确构建 JDK。");
                }
                int number = Integer.parseInt(resolved.replaceFirst("^1\\.", ""));
                if (number < 8 || number > 21) {
                    throw new Unsupported("JDK_UNSUPPORTED", "当前受控镜像支持 Java 8–21 语言级别、JDK 17/21 构建。");
                }
                level = Math.max(level, number);
            }
        }
        int recommended = level == 17 ? 17 : 21;
        return new MavenRepositoryLayout(directory(root),
                reactor.stream().map(MavenRepositoryLayout::directory).toList(), level, minimum, recommended);
    }

    private static void visit(String name, Map<String, List<String>> edges, Set<String> visiting,
            Set<String> visited, List<String> result) {
        if (!edges.containsKey(name)) {
            throw new Unsupported("MAVEN_MODULE_MISSING", "所选 reactor 声明的模块缺失或无法安全解析。");
        }
        if (!visiting.add(name)) {
            throw new Unsupported("MAVEN_MODULE_CYCLE", "Maven 模块存在循环，请检查 reactor。");
        }
        if (visited.add(name)) {
            result.add(name);
            for (String child : edges.getOrDefault(name, List.of())) {
                visit(child, edges, visiting, visited, result);
            }
        }
        visiting.remove(name);
    }

    private static Map<String, String> properties(String name, Map<String, Element> poms, Set<String> visiting) {
        if (!visiting.add(name)) {
            throw new Unsupported("MAVEN_PARENT_CYCLE", "Maven parent 引用存在循环。");
        }
        Element pom = poms.get(name);
        Map<String, String> values = new HashMap<>();
        Element parent = child(pom, "parent");
        String relative = child(parent, "relativePath") == null ? "../pom.xml" : value(parent, "relativePath");
        if (!relative.isEmpty()) {
            String local = resolve(name, relative);
            if (poms.containsKey(local)) {
                values.putAll(properties(local, poms, visiting));
            }
        }
        for (Element property : children(child(pom, "properties"), null)) {
            values.put(property.getTagName(), property.getTextContent().trim());
        }
        visiting.remove(name);
        return values;
    }

    private static String resolve(String pom, String relative) {
        if (relative.contains("\\") || relative.contains(":") || relative.startsWith("/") || relative.contains("${")) {
            throw new Unsupported("MAVEN_PATH_UNSUPPORTED", "Maven 路径必须是仓库内确定的相对路径。");
        }
        Path parent = Path.of(pom).getParent();
        String path = (parent == null ? Path.of(relative) : parent.resolve(relative)).normalize().toString().replace('\\', '/');
        return path;
    }

    private static String directory(String pom) {
        int slash = pom.lastIndexOf('/');
        return slash < 0 ? "." : pom.substring(0, slash);
    }

    private static Element child(Element root, String name) {
        if (root != null) {
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && element.getTagName().equals(name)) {
                    return element;
                }
            }
        }
        return null;
    }

    private static String value(Element root, String name) {
        Element result = child(root, name);
        return result == null ? "" : result.getTextContent().trim();
    }

    private static List<Element> children(Element root, String name) {
        List<Element> result = new ArrayList<>();
        if (root != null) {
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && (name == null || element.getTagName().equals(name))) {
                    result.add(element);
                }
            }
        }
        return result;
    }
}
