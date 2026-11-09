package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 固定启动上下文的解释性投影；不向模型转发原始 Evidence、对话或其他 Step 的修改范围。 */
public final class AgentStartupContext {
    private final UserProfileRepository profiles;
    private final RepositoryProfileRepository repositories;
    private final RepositorySecretPolicy secrets;

    public AgentStartupContext(UserProfileRepository profiles, RepositoryProfileRepository repositories,
            RepositorySecretPolicy secrets) {
        this.profiles = profiles;
        this.repositories = repositories;
        this.secrets = secrets;
    }

    ObjectNode project(ExecutionPreparationState.Loaded loaded, EvolutionStepId authorized) {
        var plan = loaded.plan();
        var direction = loaded.direction();
        var context = JsonNodeFactory.instance.objectNode();
        var rationale = context.putObject("selectedDirection");
        rationale.put("id", bounded(direction.id().value()));
        rationale.put("userFit", bounded(direction.userFit()));
        rationale.put("technicalValue", bounded(direction.technicalValue()));

        var user = context.putObject("userProfile");
        user.put("id", bounded(direction.userProfileId().value())).put("revision", direction.userProfileRevision());
        var snapshot = profiles.findSections(direction.userProfileId(), direction.userProfileRevision());
        user.put("available", snapshot.isPresent());
        snapshot.ifPresent(sections -> {
            list(user, "interests", sections.interests());
            list(user, "behaviors", sections.behaviors());
            list(user, "painPoints", sections.painPoints());
            list(user, "technicalCapabilities", sections.technicalCapabilities());
            list(user, "projectGoals", sections.projectGoals());
            list(user, "constraints", sections.constraints());
        });

        var accepted = context.putObject("acceptedPlan").put("id", bounded(plan.id().value()));
        var state = plan.currentState();
        var current = accepted.putObject("currentState").put("summary", bounded(state.summary()));
        list(current, "capabilities", state.capabilities());
        list(current, "modules", state.modules());
        list(current, "limitations", state.limitations());
        list(accepted, "reusableCapabilities", plan.reusableCapabilities());
        list(accepted, "risks", plan.risks());

        var steps = accepted.putArray("steps");
        var ordered = plan.steps();
        for (int i = 0; i < ordered.size(); i++) {
            var step = ordered.get(i);
            boolean active = authorized.equals(step.id());
            if (steps.size() >= 128 && !active) {
                continue;
            }
            steps.addObject().put("order", i + 1).put("id", bounded(step.id().value()))
                    .put("goal", bounded(step.goal())).put("status", step.status().name()).put("authorized", active);
        }
        if (steps.size() < ordered.size()) {
            accepted.put("omittedSteps", ordered.size() - steps.size());
        }

        var repository = context.putObject("historicalRepositoryProfile")
                .put("id", bounded(plan.baseRepositoryProfileId().value()));
        var historical = repositories.findById(plan.baseRepositoryProfileId())
                .filter(profile -> profile.id().equals(plan.baseRepositoryProfileId())
                        && profile.assetId().equals(plan.baseAssetId())
                        && direction.repositoryProfileIds().contains(profile.id()));
        repository.put("available", historical.isPresent());
        historical.ifPresent(profile -> {
            repository.put("analyzedRevision", bounded(profile.analyzedRevision()));
            list(repository, "techStack", profile.techStack());
        });
        return context;
    }

    private void list(ObjectNode parent, String field, List<String> items) {
        ArrayNode output = parent.putArray(field);
        items.stream().limit(16).map(this::bounded).forEach(output::add);
        if (items.size() > output.size()) {
            parent.put(field + "Omitted", items.size() - output.size());
        }
    }

    private String bounded(String value) {
        String clean = secrets.sanitizeOutput(value);
        return clean.length() <= 600 ? clean : clean.substring(0, 600) + "[已截断]";
    }
}
