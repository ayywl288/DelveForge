package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 仅阻止把尽力脱敏标记误写回源码；项目配置值不再受旧凭据行规则限制。 */
final class CredentialMutationGuard {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    CredentialMutationGuard(RepositorySecretPolicy secrets) {
    }

    record Range(int first, int count) {
        Range {
            if (first < 0 || count < 0 || (long) first + count > 1000000) {
                throw new IllegalArgumentException("Invalid bounded change range");
            }
        }

        boolean contains(int line) {
            return count > 0 && line >= first && line < first + count;
        }
    }

    record Changes(List<Range> oldLines, List<Range> newLines) {
    }

    static Changes ranges(String zeroContextDiff) {
        List<Range> oldLines = new ArrayList<>();
        List<Range> newLines = new ArrayList<>();
        for (String line : zeroContextDiff.split("\n")) {
            if (line.startsWith("@@")) {
                var matcher = HUNK.matcher(line);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Invalid controlled change ranges");
                }
                oldLines.add(new Range(Integer.parseInt(matcher.group(1)),
                        matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2))));
                newLines.add(new Range(Integer.parseInt(matcher.group(3)),
                        matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4))));
            }
        }
        return new Changes(List.copyOf(oldLines), List.copyOf(newLines));
    }

    void requireSafe(String path, String before, String after, Changes changes) {
        before = before == null ? "" : before;
        after = after == null ? "" : after;
        String[] result = after.split("\n", -1);
        for (Range range : changes.newLines()) {
            for (int line = range.first(); line < range.first() + range.count(); line++) {
                if (line < 1 || line > result.length) {
                    throw new IllegalArgumentException("Change range exceeds captured source");
                }
                if (result[line - 1].contains(DeterministicRepositorySecretPolicy.REDACTION_MARKER)) {
                    throw AgentPolicyRejection.patchContentRejectedBeforeApply();
                }
            }
        }
    }
}
