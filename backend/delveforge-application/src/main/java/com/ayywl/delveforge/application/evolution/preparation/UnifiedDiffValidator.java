package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import java.util.*;
import java.util.regex.*;

/**
 * 只接受普通文本统一补丁，按完整旧文件精确检查 hunk。
 * 不执行 Git，不接受二进制、rename、mode 或不明确的路径语法。
 */
final class UnifiedDiffValidator {
    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?");
    private final ProposalPathPolicy paths;

    UnifiedDiffValidator(ProposalPathPolicy paths) {
        this.paths = paths;
    }

    List<String> validate(String diff, Map<String, String> originals, Set<String> existing) {
        if (diff == null || !diff.endsWith("\n") || diff.indexOf(0) >= 0) {
            throw invalid();
        }
        String[] lines = diff.substring(0, diff.length() - 1).split("\n", -1);
        List<String> affected = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String gitHeader = null;
            if (lines[i].startsWith("diff --git ")) {
                gitHeader = lines[i++];
            }
            if (i + 1 >= lines.length || !lines[i].startsWith("--- ") || !lines[i + 1].startsWith("+++ ")) {
                throw invalid();
            }
            String oldHeader = lines[i++].substring(4);
            String newHeader = lines[i++].substring(4);
            String oldPath = path(oldHeader, "a/");
            String newPath = path(newHeader, "b/");
            String path = oldPath == null ? newPath : oldPath;
            if (path == null || (oldPath != null && newPath != null && !oldPath.equals(newPath))
                    || affected.contains(path)
                    || (gitHeader != null && !gitHeader.equals("diff --git a/" + path + " b/" + path))) {
                throw invalid();
            }
            if (oldPath == null ? existing.contains(path) : !existing.contains(path) || !originals.containsKey(path)) {
                throw new AiGatewayException("Existing patch targets must have been model-visible; additions must be new");
            }
            List<String> oldLines = oldPath == null ? List.of() : fileLines(originals.get(path));
            int oldEnd = 0;
            int newEnd = 0;
            int delta = 0;
            int hunks = 0;
            boolean changed = false;
            int noNewlineAt = -1;
            List<String> resultLines = new ArrayList<>();
            while (i < lines.length && lines[i].startsWith("@@ ")) {
                if (noNewlineAt >= 0) {
                    throw invalid();
                }
                Matcher hunk = HUNK.matcher(lines[i++]);
                if (!hunk.matches()) {
                    throw invalid();
                }
                int oldStart = number(hunk.group(1));
                int oldCount = hunk.group(2) == null ? 1 : number(hunk.group(2));
                int newStart = number(hunk.group(3));
                int newCount = hunk.group(4) == null ? 1 : number(hunk.group(4));
                int oldOffset = oldCount == 0 ? oldStart : oldStart - 1;
                int newOffset = newCount == 0 ? newStart : newStart - 1;
                if (oldOffset < oldEnd || newOffset < newEnd || oldOffset > oldLines.size()
                        || (long) oldOffset + oldCount > oldLines.size() || newOffset != oldOffset + delta) {
                    throw invalid();
                }
                int seenOld = 0;
                int seenNew = 0;
                resultLines.addAll(oldLines.subList(oldEnd, oldOffset));
                while (seenOld < oldCount || seenNew < newCount) {
                    if (i >= lines.length || lines[i].isEmpty()) {
                        throw invalid();
                    }
                    String line = lines[i++];
                    char kind = line.charAt(0);
                    if (kind != ' ' && kind != '-' && kind != '+') {
                        throw invalid();
                    }
                    if (kind != '+') {
                        if (seenOld >= oldCount || !oldLines.get(oldOffset + seenOld).equals(line.substring(1))) {
                            throw new AiGatewayException("Patch context does not match the expected revision");
                        }
                        seenOld++;
                    }
                    if (kind != '-') {
                        seenNew++;
                        resultLines.add(line.substring(1));
                    }
                    if (seenOld > oldCount || seenNew > newCount) {
                        throw invalid();
                    }
                    changed |= kind == '-' || kind == '+';
                    boolean marker = i < lines.length && lines[i].equals("\\ No newline at end of file");
                    if (kind != '+' && oldOffset + seenOld == oldLines.size()
                            && !originals.get(path).endsWith("\n") && !marker) {
                        throw invalid();
                    }
                    if (marker) {
                        if (kind != '+' && (oldOffset + seenOld != oldLines.size()
                                || originals.get(path).endsWith("\n"))) {
                            throw invalid();
                        }
                        if (kind != '-') {
                            if (seenNew != newCount) {
                                throw invalid();
                            }
                            noNewlineAt = newOffset + seenNew;
                        }
                        i++;
                    }
                }
                oldEnd = oldOffset + oldCount;
                newEnd = newOffset + newCount;
                delta += newCount - oldCount;
                hunks++;
            }
            if (hunks == 0 || !changed || (oldPath == null && oldEnd != 0)
                    || (newPath == null && oldLines.size() + delta != 0)
                    || (noNewlineAt >= 0 && noNewlineAt != oldLines.size() + delta)) {
                throw invalid();
            }
            resultLines.addAll(oldLines.subList(oldEnd, oldLines.size()));
            if (oldPath != null && newPath != null && resultLines.equals(oldLines)
                    && (oldEnd < oldLines.size() || (noNewlineAt >= 0) == !originals.get(path).endsWith("\n"))) {
                throw new AiGatewayException("Patch does not change file content");
            }
            affected.add(path);
        }
        if (affected.isEmpty()) {
            throw invalid();
        }
        return List.copyOf(affected);
    }

    private String path(String header, String prefix) {
        if (header.equals("/dev/null")) {
            return null;
        }
        if (!header.startsWith(prefix)) {
            throw invalid();
        }
        return paths.requireSafe(header.substring(prefix.length()));
    }

    private static List<String> fileLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        String content = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return Arrays.asList(content.split("\n", -1));
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    private static AiGatewayException invalid() {
        return new AiGatewayException("Unsupported or malformed unified diff");
    }
}
