package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.domain.agent.Candidate;

import java.util.regex.Pattern;

/** 仅为短循环比较归一化命令诊断；原始日志、进程结果与验证证据不经过此转换。 */
public final class AgentCommandDiagnostics {
    private static final String STAMP = "(?:\\d{4}-\\d{2}-\\d{2}[T ])?\\d{2}:\\d{2}:\\d{2}"
            + "(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?";
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile(
            "^(\\[(?:TRACE|DEBUG|INFO|WARN|ERROR)\\]\\s*)?\\[?" + STAMP + "\\]?\\s+");
    private static final Pattern FINISHED_AT = Pattern.compile(
            "^((?:\\[(?:TRACE|DEBUG|INFO)\\]\\s*)?Finished at:)\\s*" + STAMP + "$",
            Pattern.CASE_INSENSITIVE);
    private static final String ELAPSED_VALUE = "(?:\\d+:)*\\d+(?:[.,]\\d+)?\\s*"
            + "(?:milliseconds?|ms|seconds?|sec|s|minutes?|min|hours?|h)";
    private static final Pattern BUILD_TOTAL_TIME = Pattern.compile(
            "^((?:\\[INFO\\]\\s*)?Total time:)\\s*" + ELAPSED_VALUE + "$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_SUMMARY_TIME = Pattern.compile(
            "^((?:\\[(?:INFO|WARN|ERROR)\\]\\s*)?Tests run: \\d+, Failures: \\d+"
                    + "(?:, Errors: \\d+)?(?:, Skipped: \\d+)?, Time elapsed:)\\s*" + ELAPSED_VALUE
                    + "((?: <<< (?:FAILURE|ERROR)!)?(?: -- in \\S+)?$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUREFIRE_METHOD_TIME = Pattern.compile(
            "^((?:\\[ERROR\\]\\h+)?(?:\\p{javaJavaIdentifierPart}+\\.)+\\p{javaJavaIdentifierPart}+"
                    + "(?:\\([^()]*\\))?(?:\\[\\d+\\])?\\h+--\\h+Time elapsed:\\h+)"
                    + ELAPSED_VALUE + "(\\h+<<< (?:FAILURE|ERROR)!)$");
    private static final String COUNTER = "\\d[\\d.,]*(?:/\\d[\\d.,]*)?\\s*(?:[kMGT]?B|%)?";
    private static final Pattern PROGRESS = Pattern.compile(
            "^(?:\\[(?:TRACE|DEBUG|INFO)\\]\\s*)?Progress(?: \\(\\d+\\))?:\\s*"
                    + COUNTER + "(?:\\s*\\|\\s*" + COUNTER + ")*$");
    private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-?]*[ -/]*[@-~]");

    private AgentCommandDiagnostics() {
    }

    public static String normalize(String output) {
        StringBuilder comparable = new StringBuilder();
        String plain = ANSI.matcher(output).replaceAll("").replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : plain.split("\n")) {
            String line = TIMESTAMP_PREFIX.matcher(raw.strip()).replaceFirst("$1");
            if (line.isBlank() || PROGRESS.matcher(line).matches()) {
                continue;
            }
            line = FINISHED_AT.matcher(line).replaceFirst("$1 <timestamp>");
            // 只替换可识别统计行中的计时；断言正文里的时长是诊断值。
            line = BUILD_TOTAL_TIME.matcher(line).replaceFirst("$1 <duration>");
            line = TEST_SUMMARY_TIME.matcher(line).replaceFirst("$1 <duration>$2");
            line = SUREFIRE_METHOD_TIME.matcher(line).replaceFirst("$1<duration>$2");
            comparable.append(line).append('\n');
        }
        return comparable.toString();
    }

    /** 必须在预览截断前计算，避免噪声改变切片边界或掩盖预览之外的新错误。 */
    public static String digest(String stdout, String stderr) {
        return Candidate.digest(normalize(stdout) + "\0" + normalize(stderr));
    }
}
