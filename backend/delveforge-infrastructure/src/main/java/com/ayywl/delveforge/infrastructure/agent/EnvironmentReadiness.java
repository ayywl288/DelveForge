package com.ayywl.delveforge.infrastructure.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.regex.Pattern;

/** 只接受实际阶段证据；未知的非零退出不能被解释成可修复的源码失败。 */
final class EnvironmentReadiness {
    private static final Pattern MAVEN_PHASE = Pattern.compile(
            "(?m)^\\[INFO] --- (?:maven-)?(?:compiler|surefire|failsafe)[^\\r\\n]* ---");
    private static final Pattern ENVIRONMENT = Pattern.compile(
            "Cannot find (?:module|package)|command not found|ENOENT|ECONNREFUSED|ENOTFOUND|"
                    + "ENETUNREACH|ERR_PNPM|ERR_MODULE_NOT_FOUND|Could not resolve|"
                    + "UnsupportedClassVersionError|failed to load native|error while loading shared libraries|"
                    + "Cannot connect to|Failed to connect to|Connection refused|ConnectException|"
                    + "UnknownHostException|NoRouteToHostException|SocketTimeoutException|"
                    + "Could not find a valid Docker environment|Docker.*unavailable", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRONTEND_SOURCE = Pattern.compile(
            "error TS[0-9]+:|\\.(?:vue|[cm]?[jt]sx?)(?::|\\()[0-9]+[^\\r\\n]*(?:error|Error)|"
                    + "(?:ParseError|SyntaxError|Unexpected token|Transform failed with)");

    private EnvironmentReadiness() {
    }

    static String reason(JsonNode check) {
        return switch (check.path("classification").asText()) {
            case "DEPENDENCY_MISSING" -> "离线依赖未准备完整；核对缺失工件、公开仓库可用性与准备预算";
            case "JDK_ENVIRONMENT_MISMATCH" -> "项目要求与所选 JDK 不兼容；请核对 Java 版本";
            case "CHECK_NOT_AVAILABLE", "TEST_EXECUTION_UNVERIFIED" -> "检查脚本或结构化测试证据不可用；尚不能验证该工程";
            case "TESTS_FAILED" -> ENVIRONMENT.matcher(check.path("output").asText()).find()
                    ? "失败测试报告了缺失服务或运行条件；请检查外部服务与依赖"
                    : "失败测试缺少可信执行证据；请核对完整测试报告";
            default -> "所选检查未能建立可信执行证据；请核对工程配置及受支持的检查方式";
        };
    }

    static String classify(JsonNode check) {
        String status = check.path("classification").asText();
        String output = check.path("output").asText();
        boolean frontend = check.path("checkType").asText().equals("FRONTEND");
        boolean phase = frontend ? check.path("checkExecutions").asInt() > 0
                : MAVEN_PHASE.matcher(check.path("observedPhase").asText()).find() || MAVEN_PHASE.matcher(output).find();
        boolean tests = check.path("reports").asInt() > 0 && check.path("executedTests").asInt() > 0;
        String environmentOutput = output.replaceAll(
                "(?m)^.*Cannot find module ['\"](?:/workspace/)(?![^'\"\\r\\n]*node_modules)[^'\"\\r\\n]+['\"].*$", "");
        if (status.equals("TESTS_PASSED") && tests
                || status.equals("BUILD_PASSED_NOT_TESTED") && phase) {
            return "READY";
        }
        if (ENVIRONMENT.matcher(environmentOutput).find()) {
            return "ENVIRONMENT_BLOCKED";
        }
        if (status.equals("TESTS_FAILED") && tests
                || status.equals("COMPILATION_FAILED") && phase
                || frontend && Set.of("TYPECHECK_FAILED", "FRONTEND_BUILD_FAILED").contains(status)
                    && phase && FRONTEND_SOURCE.matcher(output).find()) {
            return "READY_BASELINE_FAILURE";
        }
        return "ENVIRONMENT_BLOCKED";
    }
}
