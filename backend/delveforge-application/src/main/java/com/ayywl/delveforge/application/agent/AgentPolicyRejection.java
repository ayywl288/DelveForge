package com.ayywl.delveforge.application.agent;

/** 安全策略拒绝只携带固定规则与阶段；不携带目标、参数或原始异常文本。 */
public final class AgentPolicyRejection extends AgentException {
    public enum Rule {
        STORAGE_LOCATION, PATH_FORMAT, PROTECTED_CREDENTIAL_PATH, FORBIDDEN_PATH, SYMLINK_PATH,
        AMBIGUOUS_GENERATED_PATH, SOURCE_ENTRY_TYPE, SOURCE_PROTECTED_ENTRY, SOURCE_CASE_CONFLICT,
        SOURCE_ENTRY_LIMIT, SOURCE_FILE_LIMIT, SOURCE_CAPTURE_LIMIT, PATCH_SIZE, PATCH_TYPE,
        PATCH_DIFF_PATHS, PATCH_FILE_MODE, PATCH_PATH_FORMAT, GENERATED_PATH, SOURCE_CREDENTIALS,
        PATCH_CREDENTIALS, COMMAND_BUDGET, REPORT_COUNT_LIMIT, UNSAFE_REPORT, REPORT_COUNTS,
        MAVEN_OPTIONS, UNSAFE_REPORT_DIRECTORY, CHECK_MUTATED_SOURCE, BASELINE_FILE_LIMIT,
        BASELINE_FILE_TYPE, UNKNOWN_TOOL, UNCLASSIFIED_POLICY
    }

    public enum Stage {
        ARGUMENTS, STORAGE, PATH_VALIDATION, PATCH_INSPECTION, PATCH_SOURCE, PATCH_CONTENT,
        PATCH_APPLICATION, SOURCE_CAPTURE, TOOL_EXECUTION
    }

    private final Rule rule;
    private final Stage stage;

    public AgentPolicyRejection(Rule rule, Stage stage) {
        super("POLICY_REJECTED", "Controlled policy rejection: " + rule.name() + "/" + stage.name());
        this.rule = rule;
        this.stage = stage;
    }

    public Rule rule() { return rule; }

    public Stage stage() { return stage; }
}
