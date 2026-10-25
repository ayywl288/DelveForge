package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentPolicyRejection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 精确原文编辑只生成标准 diff；调用方仍须执行路径、凭据检查及 Git apply 的实际写入校验。 */
final class ExactEditPatch {
    private ExactEditPatch() {
    }

    static final class Conflict extends RuntimeException {
        private final String reason;

        Conflict(String reason) {
            super("Exact edit did not match the current source");
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    static String create(String path, String current, String before, String after, int maximumBytes) {
        String updated;
        if (current == null) {
            if (!before.isEmpty()) {
                throw new Conflict("PATH_STATE_MISMATCH");
            }
            updated = after;
        } else {
            int offset = before.isEmpty() ? -1 : current.indexOf(before);
            if (offset < 0 || offset != current.lastIndexOf(before)) {
                throw new Conflict("CONTEXT_MISMATCH");
            }
            updated = current.substring(0, offset) + after + current.substring(offset + before.length());
        }
        if (updated.equals(current == null ? "" : current)) {
            throw new Conflict("CHANGE_ALREADY_PRESENT");
        }
        List<String> oldLines = lines(current == null ? "" : current);
        List<String> newLines = lines(updated);
        StringBuilder diff = new StringBuilder("--- ").append(current == null ? "/dev/null" : "a/" + path)
                .append("\n+++ b/").append(path).append("\n@@ -")
                .append(oldLines.isEmpty() ? 0 : 1).append(',').append(oldLines.size()).append(" +")
                .append(newLines.isEmpty() ? 0 : 1).append(',').append(newLines.size()).append(" @@\n");
        append(diff, oldLines, '-');
        append(diff, newLines, '+');
        String patch = diff.toString();
        if (patch.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new AgentPolicyRejection(AgentPolicyRejection.Rule.PATCH_SIZE,
                    AgentPolicyRejection.Stage.PATCH_CONTENT);
        }
        return patch;
    }

    private static List<String> lines(String value) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf('\n', start);
            end = end < 0 ? value.length() : end + 1;
            result.add(value.substring(start, end));
            start = end;
        }
        return result;
    }

    private static void append(StringBuilder diff, List<String> lines, char prefix) {
        for (String line : lines) {
            diff.append(prefix).append(line);
            if (!line.endsWith("\n")) {
                diff.append("\n\\ No newline at end of file\n");
            }
        }
    }
}
