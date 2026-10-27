package com.ayywl.delveforge.application.agent;

import java.util.List;
import java.util.Set;

/** 安全拒绝不携带参数或异常原文；源码变化只允许有界路径、类别及哈希诊断。 */
public final class AgentPolicyRejection extends AgentException {
    public enum Rule {
        STORAGE_LOCATION, PATH_FORMAT, PROTECTED_CREDENTIAL_PATH, FORBIDDEN_PATH, SYMLINK_PATH,
        AMBIGUOUS_GENERATED_PATH, SOURCE_ENTRY_TYPE, SOURCE_PROTECTED_ENTRY, SOURCE_CASE_CONFLICT,
        SOURCE_ENTRY_LIMIT, SOURCE_FILE_LIMIT, SOURCE_CAPTURE_LIMIT, PATCH_SIZE, PATCH_PREIMAGE, PATCH_TYPE,
        PATCH_DIFF_PATHS, PATCH_FILE_MODE, PATCH_PATH_FORMAT, GENERATED_PATH, SOURCE_CREDENTIALS,
        PATCH_CREDENTIALS, COMMAND_BUDGET, REPORT_COUNT_LIMIT, UNSAFE_REPORT, REPORT_COUNTS,
        MAVEN_OPTIONS, UNSAFE_REPORT_DIRECTORY, CHECK_MUTATED_SOURCE, BASELINE_FILE_LIMIT,
        BASELINE_FILE_TYPE, REQUEST_LIMIT, SOURCE_CHANGED_DURING_CAPTURE, MAVEN_CONFIGURATION, MAVEN_MODULE_LIMIT, UNKNOWN_TOOL, UNCLASSIFIED_POLICY
    }

    public enum Stage {
        ARGUMENTS, STORAGE, PATH_VALIDATION, PATCH_INSPECTION, PATCH_SOURCE, PATCH_CONTENT,
        PATCH_APPLICATION, SOURCE_CAPTURE, TOOL_EXECUTION
    }

    /** 仅定位模型自己拟提交的输入行；不含文件名、字段值、源码片段或指纹。 */
    public record RejectedInput(String field, List<Integer> lines) {
        public RejectedInput {
            if (field == null || !Set.of("patch", "new_text").contains(field) || lines == null || lines.isEmpty()
                    || lines.size() > 8 || lines.stream().anyMatch(line -> line == null || line < 1 || line > 1000000)) {
                throw new IllegalArgumentException("Invalid bounded proposal location");
            }
            lines = List.copyOf(lines);
        }
    }

    public enum SourceChangeType { ADDED, DELETED, CONTENT, MODE, CONTENT_AND_MODE }

    public record SourceFileState(String mode, String sha256) {
        public SourceFileState {
            if (mode == null || !Set.of("100644", "100755").contains(mode) || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid source state metadata");
            }
        }
    }

    public record SourceChange(String path, SourceChangeType changeType, SourceFileState before,
            SourceFileState after, boolean runtimeData) {
        public SourceChange {
            if (path == null || path.isBlank() || path.length() > 512 || path.startsWith("/")
                    || path.chars().anyMatch(value -> value < 32 || value == 127 || value == ':' || value == '\\')
                    || changeType == null) {
                throw new IllegalArgumentException("Invalid relative source metadata");
            }
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    throw new IllegalArgumentException("Invalid relative source metadata");
                }
            }
            boolean valid = switch (changeType) {
                case ADDED -> before == null && after != null;
                case DELETED -> before != null && after == null;
                case CONTENT -> before != null && after != null && before.mode().equals(after.mode())
                        && !before.sha256().equals(after.sha256());
                case MODE -> before != null && after != null && !before.mode().equals(after.mode())
                        && before.sha256().equals(after.sha256());
                case CONTENT_AND_MODE -> before != null && after != null && !before.mode().equals(after.mode())
                        && !before.sha256().equals(after.sha256());
            };
            if (!valid || runtimeData && changeType != SourceChangeType.CONTENT) {
                throw new IllegalArgumentException("Contradictory source change metadata");
            }
        }
    }

    /** 只用于 CHECK_MUTATED_SOURCE；八项以外保留计数和截断标记，不含源文件内容。 */
    public record SourceChanges(String beforeSnapshot, String afterSnapshot, int totalChanges,
            int unexpectedChanges, List<SourceChange> files, boolean truncated) {
        public SourceChanges {
            if (beforeSnapshot == null || !beforeSnapshot.matches("[0-9a-f]{64}")
                    || afterSnapshot == null || !afterSnapshot.matches("[0-9a-f]{64}")
                    || totalChanges < 1 || unexpectedChanges < 1 || unexpectedChanges > totalChanges
                    || files == null || files.isEmpty() || files.size() > 8 || files.size() > totalChanges
                    || truncated != (totalChanges > files.size())) {
                throw new IllegalArgumentException("Invalid bounded source diagnostics");
            }
            files = List.copyOf(files);
        }
    }

    private final Rule rule;
    private final Stage stage;
    private final boolean sourceEditRejectedBeforeMutation;
    private final RejectedInput rejectedInput;
    private final SourceChanges sourceChanges;

    public AgentPolicyRejection(Rule rule, Stage stage) {
        this(rule, stage, false);
    }

    private AgentPolicyRejection(Rule rule, Stage stage, boolean sourceEditRejectedBeforeMutation) {
        this(rule, stage, sourceEditRejectedBeforeMutation, null);
    }

    private AgentPolicyRejection(Rule rule, Stage stage, boolean sourceEditRejectedBeforeMutation,
            RejectedInput rejectedInput) {
        this(rule, stage, sourceEditRejectedBeforeMutation, rejectedInput, null);
    }

    private AgentPolicyRejection(Rule rule, Stage stage, boolean sourceEditRejectedBeforeMutation,
            RejectedInput rejectedInput, SourceChanges sourceChanges) {
        super("POLICY_REJECTED", "Controlled policy rejection: " + rule.name() + "/" + stage.name());
        this.rule = rule;
        this.stage = stage;
        this.sourceEditRejectedBeforeMutation = sourceEditRejectedBeforeMutation;
        this.rejectedInput = rejectedInput;
        this.sourceChanges = sourceChanges;
    }

    /** 只供已完成只读检查、尚未调用 apply_patch 的受控适配器使用。 */
    public static AgentPolicyRejection sourceEditRejectedBeforeApply() {
        return new AgentPolicyRejection(Rule.SOURCE_CREDENTIALS, Stage.PATCH_SOURCE, true);
    }

    /** 仅在只读检查确认新增补丁内容被拒绝、尚未调用实际写入命令时使用。 */
    public static AgentPolicyRejection patchContentRejectedBeforeApply() {
        return new AgentPolicyRejection(Rule.PATCH_CREDENTIALS, Stage.PATCH_CONTENT, true);
    }

    public static AgentPolicyRejection patchContentRejectedBeforeApply(RejectedInput input) {
        return new AgentPolicyRejection(Rule.PATCH_CREDENTIALS, Stage.PATCH_CONTENT, true, input);
    }

    public static AgentPolicyRejection sourceChanged(SourceChanges changes) {
        if (changes == null) {
            throw new IllegalArgumentException("Source diagnostics required");
        }
        return new AgentPolicyRejection(Rule.CHECK_MUTATED_SOURCE, Stage.TOOL_EXECUTION, false, null, changes);
    }

    public SourceChanges sourceChanges() { return sourceChanges; }

    public RejectedInput rejectedInput() { return rejectedInput; }

    public boolean sourceEditRejectedBeforeMutation() { return sourceEditRejectedBeforeMutation; }

    public Rule rule() { return rule; }

    public Stage stage() { return stage; }
}
