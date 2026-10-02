package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryPathClassifier;
import com.ayywl.delveforge.application.repositoryanalysis.scout.FileCatalogPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分支 Scout 测试共用的 Map、终态组与 **File Scout 替身**。
 *
 * <pre>
 * pkg/a/A1.java … A3.java    三个文件的分支
 * pkg/b/B1.java … B2.java    两个文件的分支
 * pkg/c/C1.java … C3.java    三个文件的分支
 * </pre>
 *
 * <p>File Scout 替身按**路径**编排：脚本里写「第 n 次调用返回哪几个区域、每个区域含哪些路径」，
 * 替身把它们换成**本次请求真实目录里的编号**。因此它复用真实的解析、引用校验与编号机制，
 * 而不是替调用方编造编号。
 */
final class BranchScoutFixtures {

    static final String REVISION = "branch-rev-1";

    static final String A1 = "pkg/a/A1.java";
    static final String A2 = "pkg/a/A2.java";
    static final String A3 = "pkg/a/A3.java";
    static final String B1 = "pkg/b/B1.java";
    static final String B2 = "pkg/b/B2.java";
    static final String C1 = "pkg/c/C1.java";
    static final String C2 = "pkg/c/C2.java";
    static final String C3 = "pkg/c/C3.java";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BranchScoutFixtures() {
    }

    static RepositoryMap map() {
        List<RepositoryMapEntry> entries = new ArrayList<>();
        int position = 1;
        for (String path : List.of(A1, A2, A3, B1, B2, C1, C2, C3)) {
            entries.add(entry(position++, path));
        }
        return RepositoryMap.of(REVISION, entries);
    }

    static RepositoryMapEntry entry(int position, String relativePath) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(relativePath);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(position),
                relativePath,
                100,
                classification.language(),
                classification.materialKind(),
                classification.roleHints());
    }

    /** 从 Map 取指定路径的描述符，并按给定 order 组成一个终态组。 */
    static RepositoryTerminalFileGroup group(RepositoryMap map, String prefix, String... paths) {
        List<String> wanted = List.of(paths);
        List<RepositoryMapEntry> selected = new ArrayList<>();
        for (RepositoryMapEntry entry : map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE)) {
            if (wanted.contains(entry.relativePath())) {
                selected.add(entry);
            }
        }
        return new RepositoryTerminalFileGroup(
                prefix, selected, catalogBytes(map, selected));
    }

    static RepositoryRegionNavigation navigation(int regionScoutCalls,
                                                 RepositoryTerminalFileGroup... groups) {
        return RepositoryRegionNavigation.of(REVISION, List.of(groups), regionScoutCalls);
    }

    /** 这组文件作为一次分支本地调用时的载荷字节数（与生产同一口径）。 */
    static int catalogBytes(RepositoryMap map, List<RepositoryMapEntry> entries) {
        List<RepositoryMapEntry> renumbered = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            RepositoryMapEntry entry = entries.get(index);
            renumbered.add(new RepositoryMapEntry(
                    RepositoryFileReference.of(index + 1),
                    entry.relativePath(),
                    entry.sizeInBytes(),
                    entry.language(),
                    entry.materialKind(),
                    entry.roleHints()));
        }
        return new FileCatalogPayload(MAPPER).payloadBytes(map.analyzedRevision(), renumbered);
    }

    /**
     * 脚本化的 File Scout 替身。
     *
     * <p>脚本是「调用 → 区域 → 路径列表」：第 n 次调用返回第 n 项，其中的区域顺序与路径顺序
     * 就是模型表达的顺序。路径会被换成本次请求目录里对应的编号。
     */
    static final class ScriptedFileScoutGateway implements AiGateway {

        private final List<List<List<String>>> script;
        private final List<AiRequest> requests = new ArrayList<>();

        ScriptedFileScoutGateway(List<List<List<String>>> script) {
            this.script = script;
        }

        @Override
        public String generate(AiRequest request) {
            int call = requests.size();
            requests.add(request);
            if (call >= script.size()) {
                throw new AssertionError(
                        "第 " + (call + 1) + " 次 File Scout 调用超出脚本（共 "
                                + script.size() + " 次）");
            }
            Map<String, String> referenceByPath = referencesByPath(request);

            List<Map<String, Object>> areas = new ArrayList<>();
            int area = 0;
            for (List<String> paths : script.get(call)) {
                List<String> references = new ArrayList<>();
                for (String path : paths) {
                    // 以 RF- 开头的项按**字面编号**原样发出：用来构造「引用本次没有提供的编号」。
                    String reference = path.startsWith("RF-") ? path : referenceByPath.get(path);
                    if (reference == null) {
                        throw new AssertionError(
                                "脚本里的路径不在本次分支目录中: " + path);
                    }
                    references.add(reference);
                }
                areas.add(Map.of("label", "区域" + (++area), "fileRefs", references));
            }
            try {
                return MAPPER.writeValueAsString(Map.of("focusAreas", areas));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        int calls() {
            return requests.size();
        }

        /** 本次请求的目录里出现的路径，按目录顺序。 */
        List<String> pathsOf(int call) {
            Map<String, String> byPath = referencesByPath(requests.get(call));
            return List.copyOf(byPath.keySet());
        }

        /** 本次请求的目录里出现的编号，按目录顺序。 */
        List<String> referencesOf(int call) {
            Map<String, String> byPath = referencesByPath(requests.get(call));
            return List.copyOf(byPath.values());
        }

        private static Map<String, String> referencesByPath(AiRequest request) {
            List<AiMessage> messages = request.messages();
            String userMessage = messages.get(messages.size() - 1).content();
            try {
                JsonNode catalog = MAPPER.readTree(userMessage).get("fileCatalog");
                Map<String, String> byPath = new LinkedHashMap<>();
                for (JsonNode entry : catalog) {
                    byPath.put(entry.get("path").asText(), entry.get("reference").asText());
                }
                return byPath;
            } catch (Exception exception) {
                throw new AssertionError("无法从请求里读出 File Catalog", exception);
            }
        }
    }
}
