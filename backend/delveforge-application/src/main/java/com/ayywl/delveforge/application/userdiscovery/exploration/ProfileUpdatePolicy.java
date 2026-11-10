package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.userdiscovery.shared.ProfilePromptContext;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 整区建议的确定性写入边界；精确前缀只判断追加形状，不证明语义真实性。 */
public final class ProfileUpdatePolicy {

    public enum Classification { UNCHANGED, AUTO_APPEND, REQUIRES_APPROVAL }

    public record Update(Map<String, List<String>> sections, List<String> evidenceClaims, boolean approvalRequired) {
        public Update {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            sections.forEach((key, values) -> copy.put(key, List.copyOf(values)));
            sections = java.util.Collections.unmodifiableMap(copy);
            evidenceClaims = List.copyOf(evidenceClaims);
        }
    }

    private ProfileUpdatePolicy() {
    }

    public static Classification classify(List<String> current, List<String> proposed, boolean protectedSection) {
        if (current.equals(proposed)) {
            return Classification.UNCHANGED;
        }
        if (protectedSection || proposed.size() <= current.size()
                || !proposed.subList(0, current.size()).equals(current)) {
            return Classification.REQUIRES_APPROVAL;
        }
        java.util.Set<String> seen = new java.util.HashSet<>(current);
        for (String value : proposed.subList(current.size(), proposed.size())) {
            if (!seen.add(value)) {
                return Classification.REQUIRES_APPROVAL;
            }
        }
        return Classification.AUTO_APPEND;
    }

    /** 先用隔离副本完成全部领域校验，再分类；任一区需批准时整轮都不写入。 */
    public static Update evaluate(UserProfile current, UserProfileProposal proposal, List<String> protectedSections) {
        Map<String, List<String>> provided = new LinkedHashMap<>();
        provided.put("interests", proposal.interests());
        provided.put("behaviors", proposal.behaviors());
        provided.put("painPoints", proposal.painPoints());
        provided.put("technicalCapabilities", proposal.technicalCapabilities());
        provided.put("projectGoals", proposal.projectGoals());
        provided.put("constraints", proposal.constraints());
        provided.values().removeIf(java.util.Objects::isNull);
        applySections(UserProfileCandidates.copyOf(current), provided);
        if (proposal.evidenceClaims() != null) {
            for (String claim : proposal.evidenceClaims()) {
                new Evidence(EvidenceSourceType.USER_INPUT, "本轮输入", claim, null, false);
            }
        }
        Map<String, List<String>> changed = new LinkedHashMap<>();
        boolean approval = false;
        Map<String, List<String>> before = ProfilePromptContext.currentContent(current);
        for (var entry : provided.entrySet()) {
            Classification classification = classify(before.get(entry.getKey()), entry.getValue(),
                    protectedSections.contains(entry.getKey()));
            if (classification != Classification.UNCHANGED) {
                changed.put(entry.getKey(), entry.getValue());
                approval |= classification == Classification.REQUIRES_APPROVAL;
            }
        }
        // 仅重述旧内容或生成额外解释，不制造新的内容版本或模型依据。
        List<String> claims = changed.isEmpty() || proposal.evidenceClaims() == null
                ? List.of() : proposal.evidenceClaims();
        return new Update(changed, claims, approval);
    }

    public static void apply(UserProfile candidate, Update update, String input) {
        applySections(candidate, update.sections());
        for (String claim : update.evidenceClaims()) {
            candidate.recordEvidence(new Evidence(EvidenceSourceType.USER_INPUT, input, claim, null, false));
        }
    }

    public static void applySections(UserProfile candidate, Map<String, List<String>> sections) {
        sections.forEach((section, values) -> {
            switch (section) {
                case "interests" -> candidate.updateInterests(values);
                case "behaviors" -> candidate.updateBehaviors(values);
                case "painPoints" -> candidate.updatePainPoints(values);
                case "technicalCapabilities" -> candidate.updateTechnicalCapabilities(values);
                case "projectGoals" -> candidate.updateProjectGoals(values);
                case "constraints" -> candidate.updateConstraints(values);
                default -> throw new IllegalArgumentException("未知画像分区：" + section);
            }
        });
    }
}
