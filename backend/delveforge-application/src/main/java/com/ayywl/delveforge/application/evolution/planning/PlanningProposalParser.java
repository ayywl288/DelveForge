package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evolution.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 严格校验字段结构，将本次调用的 Evidence 短引用还原为真实依据。
 * 额外字段沿用既有 AI 解析器的忽略行为，不能据此制造领域状态。
 */
public final class PlanningProposalParser {
    private final AiJsonObjectReader reader;

    public PlanningProposalParser(ObjectMapper mapper) {
        reader = new AiJsonObjectReader(mapper);
    }

    public PlanningProposal parse(String raw, Map<String, EvidenceBasis> catalog) {
        try {
            return parseContent(raw, catalog);
        } catch (IllegalArgumentException exception) {
            throw new AiGatewayException("AI planning content is incomplete", exception);
        }
    }

    private PlanningProposal parseContent(String raw, Map<String, EvidenceBasis> catalog) {
        JsonNode root = reader.read(raw);
        JsonNode current = object(root, "currentState");
        JsonNode target = object(root, "targetState");

        List<PlanningStepProposal> steps = new ArrayList<>();
        for (JsonNode step : array(root, "steps")) {
            if (!step.isObject()) {
                throw new AiGatewayException("Planning step must be an object");
            }
            steps.add(new PlanningStepProposal(text(step, "goal"), text(step, "scope"),
                    section(step, "plannedChanges"), section(step, "preconditions"), section(step, "verificationCriteria")));
        }

        List<EvidenceBasis> evidence = new ArrayList<>();
        for (String reference : section(root, "evidence")) {
            EvidenceBasis basis = catalog.get(reference);
            if (basis == null) {
                throw new AiGatewayException("Unknown planning Evidence reference");
            }
            evidence.add(basis);
        }

        try {
            return new PlanningProposal(
                    new CurrentState(text(current, "summary"), section(current, "capabilities"),
                            section(current, "modules"), section(current, "limitations")),
                    new TargetState(text(target, "problem"), text(target, "targetProduct"), text(target, "differentiation")),
                    section(root, "reusableCapabilities"), section(root, "changes"), steps, section(root, "risks"), evidence);
        } catch (IllegalArgumentException exception) {
            throw new AiGatewayException("AI planning content is incomplete", exception);
        }
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new AiGatewayException("Planning object required: " + field);
        }
        return value;
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new AiGatewayException("Planning array required: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new AiGatewayException("Planning text required: " + field);
        }
        return value.textValue();
    }

    private static List<String> section(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new AiGatewayException("Planning array must contain nonblank strings: " + field);
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }
}
