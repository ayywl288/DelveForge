package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 严格解析 Coding Gateway 的两种 JSON 响应；结构合法不代表补丁事实或执行资格成立。 */
public final class CodingProposalParser {

    private final AiJsonObjectReader reader;
    private final ExecutionPreparationLimits limits;

    public CodingProposalParser(ObjectMapper mapper, ExecutionPreparationLimits limits) {
        this.reader = new AiJsonObjectReader(mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
        this.limits = limits;
    }

    public CodingResponse parse(String output) {
        if (output == null || output.getBytes(StandardCharsets.UTF_8).length > limits.maxProposalBytes()) {
            throw new AiGatewayException("Coding response exceeds budget");
        }

        JsonNode root = reader.read(output);
        String type = text(root, "type");
        String revision = text(root, "expectedRevision");
        if ("NEED_MORE_CONTEXT".equals(type)) {
            fields(root, Set.of("type", "expectedRevision", "missingDependency", "pathPrefix"));
            return new CodingResponse.NeedMore(revision, text(root, "missingDependency"), text(root, "pathPrefix"));
        }
        if (!"CHANGE_PROPOSAL".equals(type)) {
            throw new AiGatewayException("Unknown coding response type");
        }

        fields(root, Set.of("type", "expectedRevision", "summary", "declaredAffectedPaths",
                "unifiedDiff", "verificationStrategy"));
        List<String> paths = new ArrayList<>();
        for (JsonNode path : array(root, "declaredAffectedPaths", limits.maxAffectedPaths())) {
            if (!path.isTextual() || path.asText().isBlank()) {
                throw new AiGatewayException("Affected paths must be strings");
            }
            paths.add(path.asText());
        }

        List<CodingProposal.VerificationAction> checks = new ArrayList<>();
        for (JsonNode check : array(root, "verificationStrategy", limits.maxVerificationChecks())) {
            fields(check, Set.of("action", "target", "criterionIndexes"));
            CodingProposal.Action action;
            try {
                action = CodingProposal.Action.valueOf(text(check, "action"));
            } catch (IllegalArgumentException invalid) {
                throw new AiGatewayException("Unsupported verification action", invalid);
            }
            JsonNode target = check.get("target");
            if (target == null || !target.isTextual() || target.asText().length() > 256
                    || !target.asText().matches("[A-Za-z0-9_./#$-]*")
                    || (action == CodingProposal.Action.FOCUSED_TEST && target.asText().isBlank())
                    || (action != CodingProposal.Action.FOCUSED_TEST && !target.asText().isEmpty())) {
                throw new AiGatewayException("Verification target must be bounded test intent, not a command");
            }
            List<Integer> indexes = new ArrayList<>();
            JsonNode criterionIndexes = check.get("criterionIndexes");
            if (criterionIndexes == null || !criterionIndexes.isArray()) {
                throw new AiGatewayException("criterionIndexes must be an array");
            }
            for (JsonNode index : criterionIndexes) {
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.asInt() <= 0) {
                    throw new AiGatewayException("Criterion references must be positive integer indexes");
                }
                indexes.add(index.asInt());
            }
            checks.add(new CodingProposal.VerificationAction(action, target.asText(), indexes));
        }
        return new CodingResponse.Change(new CodingProposal(revision, text(root, "summary"), paths,
                text(root, "unifiedDiff"), checks));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new AiGatewayException("Coding response lacks required text");
        }
        return node.asText();
    }

    private static JsonNode array(JsonNode root, String field, int max) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > max) {
            throw new AiGatewayException("Coding response array is absent, empty or over budget");
        }
        return node;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject()) {
            throw new AiGatewayException("Coding response must be an object");
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new AiGatewayException("Coding response fields do not match the contract");
        }
    }
}
