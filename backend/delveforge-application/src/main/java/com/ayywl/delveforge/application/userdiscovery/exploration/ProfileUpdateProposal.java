package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.userdiscovery.shared.ProfilePromptContext;
import com.ayywl.delveforge.domain.user.UserProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Application 的有界整轮审批快照；不属于新的领域 Aggregate。 */
public record ProfileUpdateProposal(String id, String profileId, String sourceRequestId, String projectId,
        int baseRevision, String baseStatus, Map<String, List<String>> currentSections,
        Map<String, List<String>> proposedSections, String userInput, List<String> evidenceClaims,
        String state, Integer resultingRevision) {

    public ProfileUpdateProposal {
        currentSections = snapshot(currentSections);
        proposedSections = snapshot(proposedSections);
        evidenceClaims = List.copyOf(evidenceClaims);
    }

    public static ProfileUpdateProposal pending(UserProfile profile, String requestId, String projectId,
            String input, ProfileUpdatePolicy.Update update) {
        Map<String, List<String>> current = new LinkedHashMap<>();
        Map<String, List<String>> content = ProfilePromptContext.currentContent(profile);
        update.sections().keySet().forEach(section -> current.put(section, content.get(section)));
        return new ProfileUpdateProposal(UUID.randomUUID().toString(), profile.id().value(), requestId, projectId,
                profile.revision(), profile.status().name(), current, update.sections(), input,
                update.evidenceClaims(), "PENDING", null);
    }

    public ProfileUpdateProposal resolved(String nextState, Integer revision) {
        return new ProfileUpdateProposal(id, profileId, sourceRequestId, projectId, baseRevision, baseStatus,
                currentSections, proposedSections, userInput, evidenceClaims, nextState, revision);
    }

    private static Map<String, List<String>> snapshot(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, items) -> copy.put(key, List.copyOf(items)));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
