package com.ayywl.delveforge.application.userdiscovery.conversation;

import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import java.util.List;

/** 当前内容版本的最低条件判断，与聊天是否继续及用户是否进入审阅相互独立。 */
public record DiscoveryReadiness(boolean sufficient, List<String> missingAreas) {

    public DiscoveryReadiness {
        missingAreas = List.copyOf(missingAreas);
    }

    public SufficiencyAssessment assessment(UserProfileStatus status) {
        return new SufficiencyAssessment(sufficient, missingAreas, null, status);
    }
}
