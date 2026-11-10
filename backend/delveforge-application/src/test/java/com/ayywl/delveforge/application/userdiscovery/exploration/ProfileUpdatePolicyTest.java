package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileUpdatePolicyTest {

    @Test
    void exactPrefixAppendIsTheOnlyUnattendedContentChange() {
        var skills = List.of("Java", "Spring Boot");
        assertEquals(ProfileUpdatePolicy.Classification.UNCHANGED,
                ProfileUpdatePolicy.classify(skills, skills, true));
        assertEquals(ProfileUpdatePolicy.Classification.AUTO_APPEND,
                ProfileUpdatePolicy.classify(skills, List.of("Java", "Spring Boot", "Redis"), false));
        for (var proposed : List.of(List.of("Spring Boot", "Java"), List.of("Java后端", "Spring Boot"),
                List.of("Java"), List.<String>of(), List.of("Java", "Spring Boot", "Redis", "Redis"),
                List.of("Java", "Spring Boot", "Java"))) {
            assertEquals(ProfileUpdatePolicy.Classification.REQUIRES_APPROVAL,
                    ProfileUpdatePolicy.classify(skills, proposed, false));
        }
        assertEquals(ProfileUpdatePolicy.Classification.REQUIRES_APPROVAL,
                ProfileUpdatePolicy.classify(skills, List.of("Java", "Spring Boot", "Redis"), true));
        assertEquals(ProfileUpdatePolicy.Classification.REQUIRES_APPROVAL,
                ProfileUpdatePolicy.classify(List.of(), List.of("旧值"), true));
    }

    @Test
    void oneProtectedChangeStagesAllSectionsAndValidationPrecedesAnyMutation() {
        var profile = UserProfile.create(new UserProfileId("policy"));
        profile.updateTechnicalCapabilities(List.of("Java", "Spring Boot"));
        var proposal = new UserProfileProposal(null, List.of("整理笔记"), null,
                List.of("Java", "Spring Boot", "Redis"), null, null, List.of("最近练习Redis"));
        var update = ProfileUpdatePolicy.evaluate(profile, proposal, List.of("technicalCapabilities"));
        assertTrue(update.approvalRequired());
        assertEquals(2, update.sections().size());
        assertEquals(List.of(), profile.behaviors());
        assertEquals(List.of("Java", "Spring Boot"), profile.technicalCapabilities());
        assertThrows(IllegalArgumentException.class, () -> ProfileUpdatePolicy.evaluate(profile,
                new UserProfileProposal(null, List.of("新行为"), null, List.of(" "), null, null, null), List.of()));
        assertEquals(List.of(), profile.behaviors());
    }

    @Test
    void unchangedOrEvidenceOnlyModelOutputDoesNotCreateAContentRevision() {
        var profile = UserProfile.create(new UserProfileId("unchanged"));
        var update = ProfileUpdatePolicy.evaluate(profile,
                new UserProfileProposal(List.of(), null, null, null, null, null, List.of("凭空判断")), List.of());
        ProfileUpdatePolicy.apply(profile, update, "没有新信息");
        assertTrue(update.sections().isEmpty());
        assertTrue(update.evidenceClaims().isEmpty());
        assertFalse(update.approvalRequired());
        assertEquals(1, profile.revision());
        assertEquals(List.of(), profile.evidence());
    }
}
