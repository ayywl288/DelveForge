package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 原生命令证据：验证意图来自 Agent，结果、调用身份和源码身份只能由运行时产生。 */
public final class CommandCheckEvidence {
    private CommandCheckEvidence() {
    }

    public static String key(String command, String directory) {
        return com.ayywl.delveforge.domain.agent.Candidate.digest(directory + "\n" + command);
    }

    /** 不把输出说明或空操作当作项目检查；实际检查语义仍由仓库指引和 Step 范围决定。 */
    public static boolean trivial(String command) {
        String value = command.strip();
        return value.matches("(?s)(?:(?:echo|printf|true|false|pwd|ls|cat|exit|:)(?:\\s[^;&|]*)?\\s*[;&|]*\\s*)+");
    }

    public static void requireAcceptable(JsonNode evidence) {
        String snapshot = evidence.path("finalSnapshot").asText();
        if (!snapshot.matches("[0-9a-f]{64}") || !evidence.path("checks").isArray()
                || !evidence.path("requiredChecks").isArray()
                || !Set.of("TESTED", "NOT_TESTED").contains(evidence.path("status").asText())) {
            throw new AgentException("EVIDENCE_INVALID", "Command receipts require a final source identity and scope");
        }
        if (evidence.path("sourceView").path("withheldSourceCount").asInt() > 0) {
            throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Required source is withheld from the execution view");
        }
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        Set<String> invocations = new java.util.HashSet<>();
        for (JsonNode check : evidence.path("checks")) {
            if (!check.path("checkType").asText().equals("COMMAND")
                    || check.path("invocationId").asText().isBlank()
                    || !invocations.add(check.path("invocationId").asText())
                    || !check.path("exitCode").isIntegralNumber()
                    || !check.path("timedOut").isBoolean() || !check.path("cancelled").isBoolean()
                    || !check.path("snapshot").asText().matches("[0-9a-f]{64}")
                    || !check.path("afterSnapshot").asText().matches("[0-9a-f]{64}")) {
                throw new AgentException("EVIDENCE_INVALID", "Incomplete command receipt");
            }
            latest.put(key(check.path("command").asText(), check.path("directory").asText()), check);
        }
        if (evidence.path("requiredChecks").isEmpty()) {
            throw new AgentException("UNRESOLVED_CHECK_FAILURE", "No task/repository verification scope was executed");
        }
        Set<String> required = new java.util.HashSet<>();
        for (JsonNode requirement : evidence.path("requiredChecks")) {
            String command = requirement.path("command").asText();
            String directory = requirement.path("directory").asText();
            String identity = key(command, directory);
            if (command.isBlank() || directory.isBlank() || requirement.path("scope").asText().isBlank()
                    || !Set.of("test", "build", "other").contains(requirement.path("kind").asText())
                    || trivial(command) || !required.add(identity)) {
                throw new AgentException("EVIDENCE_INVALID", "Invalid or duplicate verification obligation");
            }
            JsonNode check = latest.get(identity);
            if (check == null || check.path("exitCode").asInt() != 0
                    || check.path("timedOut").asBoolean() || check.path("cancelled").asBoolean()
                    || !snapshot.equals(check.path("snapshot").asText())
                    || !snapshot.equals(check.path("afterSnapshot").asText())
                    || !check.path("classification").asText().equals("COMMAND_SUCCEEDED")) {
                throw new AgentException("UNRESOLVED_CHECK_FAILURE",
                        "Missing, failed or source-stale required command: " + command + " (" + directory + ")");
            }
            JsonNode report = check.path("testReport");
            if (report.path("availability").asText().equals("AVAILABLE")
                    && (report.path("executedTests").asInt() <= 0 || report.path("failures").asInt() > 0)) {
                throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Available test report contradicts successful verification");
            }
            // 可用报告补充实际命令证据：已观察到的失败不能靠删除/跳过该用例消失。
            // 没有报告的仓库仍按原生命令验证；不强制所有成功运行保持同一测试清单。
            for (JsonNode previous : evidence.path("checks")) {
                if (!identity.equals(key(previous.path("command").asText(), previous.path("directory").asText()))) {
                    continue;
                }
                var cases = previous.path("testReport").path("cases").fields();
                while (cases.hasNext()) {
                    var entry = cases.next();
                    if (entry.getValue().asText().equals("FAILED")
                            && !report.path("cases").path(entry.getKey()).asText().equals("PASSED")) {
                        throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Previously failing test was not observed passing");
                    }
                }
            }
        }
    }
}
