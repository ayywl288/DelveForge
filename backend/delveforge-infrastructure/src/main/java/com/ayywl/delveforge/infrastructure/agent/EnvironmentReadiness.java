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
