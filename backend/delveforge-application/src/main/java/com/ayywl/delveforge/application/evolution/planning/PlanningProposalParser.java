package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 严格校验 AI 协议字段结构；临时 Evidence 引用由 PlanningProposalResolver 还原。
 * 已移除的 Step preconditions 明确拒绝；其余额外字段不能制造领域状态。
 */
public final class PlanningProposalParser {
    private final AiJsonObjectReader reader;

    public PlanningProposalParser(ObjectMapper mapper) {
        reader = new AiJsonObjectReader(mapper);
    }

    public AiPlanningProposal parse(String raw) {
        try {
            return parseContent(raw);
        } catch (AiGatewayException | IllegalArgumentException exception) {
            throw new PlanningExtractionException(PlanningExtractionException.Phase.PROPOSAL_PARSE, exception);
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
            if (step.has("preconditions")) {
                throw new AiGatewayException("Step preconditions are not supported; regenerate the planning proposal");
            }
            steps.add(new AiPlanningStepProposal(text(step, "goal"), text(step, "scope"),
                    requiredSection(step, "plannedChanges"),
                    criteria(step)));
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

    private static List<VerificationCriterion> criteria(JsonNode step) {
        List<VerificationCriterion> result = new ArrayList<>();
        for (JsonNode criterion : array(step, "verificationCriteria")) {
            if (!criterion.isObject()) {
                throw new AiGatewayException("Verification criterion must contain kind and description");
            }
            result.add(new VerificationCriterion(VerificationCriterion.Kind.valueOf(text(criterion, "kind")),
                    text(criterion, "description")));
        }
        if (result.isEmpty()) {
            throw new AiGatewayException("Verification criteria must not be empty");
        }
        return List.copyOf(result);
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
