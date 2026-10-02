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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分层导航测试共用的 Maps、字节度量与 Region Scout 替身。
 *
 * <h2>三张 Map</h2>
 *
 * <pre>
 * map()             三层结构，用于递归、优先级与直接文件
 *   main.py                       仓库根目录直属
 *   pkg/aaa/A.java … C.java       pkg/aaa 下三个直属文件（叶子）
 *   pkg/bbb/D.java
 *   pkg/top.java                  pkg 下的直属文件
 *   tool/E.py
 *
 * flatMap()         一个扁平目录：flat/ 下 8 个文件，没有任何子目录
 *
 * directFilesMap()  pkg 下有 9 个直属文件 + 一个子目录 pkg/sub
 * </pre>
 *
 * <p>三张 Map 的条目**全部**是 SCOUT_SOURCE，因此编号就是 {@code RF-1…RF-n}，
 * 「整份集合」与「按分支本地重新编号后的子集」可以直接对照。
 */
final class RegionNavigationFixtures {

    static final String REVISION = "nav-rev-1";

    static final String ROOT_MAIN = "main.py";
    static final String PKG_AAA_A = "pkg/aaa/A.java";
    static final String PKG_AAA_B = "pkg/aaa/B.java";
    static final String PKG_AAA_C = "pkg/aaa/C.java";
    static final String PKG_BBB_D = "pkg/bbb/D.java";
    static final String PKG_TOP = "pkg/top.java";
    static final String TOOL_E = "tool/E.java";

    /** pkg/aaa 分支（恰好可以在测试里作为「装得下」的基准）。 */
    static final List<String> PKG_AAA_FILES = List.of(PKG_AAA_A, PKG_AAA_B, PKG_AAA_C);

    /** pkg 分支的全部文件。 */
    static final List<String> PKG_FILES =
            List.of(PKG_AAA_A, PKG_AAA_B, PKG_AAA_C, PKG_BBB_D, PKG_TOP);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RegionNavigationFixtures() {
    }

    static RepositoryMap map() {
        return RepositoryMap.of(REVISION, List.of(
                entry(1, ROOT_MAIN, 100),
                entry(2, PKG_AAA_A, 200),
                entry(3, PKG_AAA_B, 210),
                entry(4, PKG_AAA_C, 220),
                entry(5, PKG_BBB_D, 300),
                entry(6, PKG_TOP, 90),
                entry(7, TOOL_E, 80)));
    }

    /** 扁平目录：flat/ 下 8 个文件，没有任何子目录。 */
    static RepositoryMap flatMap() {
        List<RepositoryMapEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            entries.add(entry(i, "flat/F" + i + ".java", 100 + i));
        }
        return RepositoryMap.of(REVISION, entries);
    }

    /** pkg 下 9 个直属文件 + 一个子目录 pkg/sub：子目录存在，但直属文件本身就装不下。 */
    static RepositoryMap directFilesMap() {
        List<RepositoryMapEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            entries.add(entry(i, "pkg/" + i + ".java", 100 + i));
        }
        entries.add(entry(10, "pkg/sub/A.java", 150));
        entries.add(entry(11, "root.java", 50));
        return RepositoryMap.of(REVISION, entries);
    }

    /** 两个各自需要下钻的分支：用于「总调用数」被宽度而非深度耗尽的情形。 */
    static RepositoryMap twoBranchesMap() {
        List<RepositoryMapEntry> entries = new ArrayList<>();
        int position = 1;
        entries.add(entry(position++, "alpha/top.java", 90));
        for (String name : List.of("A", "B", "C", "D", "E")) {
            entries.add(entry(position++, "alpha/x/" + name + ".java", 100));
        }
        entries.add(entry(position++, "alpha/y/F.java", 100));
        entries.add(entry(position++, "beta/top.java", 90));
        for (String name : List.of("G", "H", "I", "J", "K")) {
            entries.add(entry(position++, "beta/p/" + name + ".java", 100));
        }
        entries.add(entry(position, "beta/q/L.java", 100));
        return RepositoryMap.of(REVISION, entries);
    }

    /** {@link #twoBranchesMap()} 里 alpha/x 的五个文件——作为「刚好装得下」的基准。 */
    static final List<String> ALPHA_X_FILES = List.of(
            "alpha/x/A.java", "alpha/x/B.java", "alpha/x/C.java",
            "alpha/x/D.java", "alpha/x/E.java");

    static RepositoryMapEntry entry(int position, String relativePath, long sizeInBytes) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(relativePath);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(position),
                relativePath,
                sizeInBytes,
                classification.language(),
                classification.materialKind(),
                classification.roleHints());
    }

    /**
     * 一组路径作为**一次分支本地 File Scout 目录**时的载荷字节数。
     *
     * <p>与导航内部同一口径：按 Map 顺序过滤、按位置重新编号（{@code RF-1…RF-n}）、
     * 用 File Scout 实际发送的那个渲染入口序列化。
     */
    static int catalogBytes(RepositoryMap map, String... paths) {
        return catalogBytes(map, List.of(paths));
    }

    static int catalogBytes(RepositoryMap map, List<String> paths) {
        Set<String> wanted = new LinkedHashSet<>(paths);
        List<RepositoryMapEntry> selected = new ArrayList<>();
        for (RepositoryMapEntry entry : map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE)) {
            if (wanted.contains(entry.relativePath())) {
                selected.add(entry);
            }
        }
        return payload().payloadBytes(map.analyzedRevision(), renumber(selected));
    }

    static FileCatalogPayload payload() {
        return new FileCatalogPayload(MAPPER);
    }

    static List<RepositoryMapEntry> renumber(List<RepositoryMapEntry> entries) {
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
        return List.copyOf(renumbered);
    }

    /**
     * 脚本化的 Region Scout 替身。
     *
     * <p>它按第 n 次调用取脚本里第 n 项（1 起的位置），从**本次请求的真实目录**里取出对应编号
     * 组成响应。因此它复用的是真实的解析、引用校验与作用域机制，而不是绕过它们。
     */
    static final class ScriptedScoutGateway implements AiGateway {

        private final List<List<Integer>> script;
        private final List<AiRequest> requests = new ArrayList<>();

        ScriptedScoutGateway(List<List<Integer>> script) {
            this.script = script;
        }

        @Override
        public String generate(AiRequest request) {
            int call = requests.size();
            requests.add(request);
            if (call >= script.size()) {
                throw new AssertionError(
                        "第 " + (call + 1) + " 次 Region Scout 调用超出脚本（共 "
                                + script.size() + " 次）——导航调用的次数与预期不符");
            }
            List<String> references = referencesFrom(request);
            String refs = script.get(call).stream()
                    .map(position -> "\"" + references.get(position - 1) + "\"")
                    .collect(Collectors.joining(","));
            return "{\"regionRefs\":[" + refs + "]}";
        }

        int calls() {
            return requests.size();
        }

        List<AiRequest> requests() {
            return List.copyOf(requests);
        }

        /** 从本次请求的用户消息里读出目录中按顺序排列的编号。 */
        private static List<String> referencesFrom(AiRequest request) {
            List<AiMessage> messages = request.messages();
            String userMessage = messages.get(messages.size() - 1).content();
            try {
                JsonNode catalog = MAPPER.readTree(userMessage).get("regionCatalog");
                List<String> references = new ArrayList<>();
                for (JsonNode region : catalog) {
                    references.add(region.get("reference").asText());
                }
                return references;
            } catch (Exception exception) {
                throw new AssertionError("无法从请求里读出 Region 目录", exception);
            }
        }
    }
}
