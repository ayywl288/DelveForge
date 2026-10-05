package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 严格校验 AI 协议字段结构；临时 Evidence 引用由 PlanningProposalResolver 还原。
 * 额外字段沿用既有 AI 解析器的忽略行为，不能据此制造领域状态。
 */
public final class PlanningProposalParser {
    private final AiJsonObjectReader reader;

    public PlanningProposalParser(ObjectMapper mapper) {
        reader = new AiJsonObjectReader(mapper);
    }

    public AiPlanningProposal parse(String raw) {
        try {
            return parseContent(raw);
        } catch (IllegalArgumentException exception) {
            throw new AiGatewayException("AI planning content is incomplete", exception);
        }
    }

    private AiPlanningProposal parseContent(String raw) {
        JsonNode root = reader.read(raw);
        JsonNode current = object(root, "currentState");
        JsonNode target = object(root, "targetState");

        List<AiPlanningStepProposal> steps = new ArrayList<>();
        for (JsonNode step : array(root, "steps")) {
            if (!step.isObject()) {
                throw new AiGatewayException("Planning step must be an object");
            }
            steps.add(new AiPlanningStepProposal(text(step, "goal"), text(step, "scope"),
                    requiredSection(step, "plannedChanges"), section(step, "preconditions"),
                    requiredSection(step, "verificationCriteria")));
        }

        return new AiPlanningProposal(
                new CurrentState(text(current, "summary"), section(current, "capabilities"),
                        section(current, "modules"), section(current, "limitations")),
                new TargetState(text(target, "problem"), text(target, "targetProduct"), text(target, "differentiation")),
                section(root, "reusableCapabilities"), requiredSection(root, "changes"), steps,
                section(root, "risks"), section(root, "evidence"));
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

    private static List<String> requiredSection(JsonNode node, String field) {
        List<String> values = section(node, field);
        if (values.isEmpty()) {
            throw new AiGatewayException("Planning array must not be empty: " + field);
        }
        return values;
    }
}
