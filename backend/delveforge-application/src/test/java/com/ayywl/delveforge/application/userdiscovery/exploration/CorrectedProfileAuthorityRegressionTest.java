package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileRequest;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 同一测试可在旧基线上运行，证明模型伪造 replacement 不构成人工授权。 */
class CorrectedProfileAuthorityRegressionTest {

    @Test
    void unrelatedReadmeQuotationCannotAuthorizeRestoringOldConstraint() {
        var repository = new InMemoryUserProfileRepository();
        var profile = UserProfile.create(new UserProfileId("f001"));
        profile.updateConstraints(List.of("一个晚上完成"));
        repository.save(profile);
        var corrected = new UpdateUserProfileUseCase(repository).update(new UpdateUserProfileRequest(profile.id(),
                null, null, null, null, null, List.of("约两小时完成"), null));
        var extraction = new ProfileExtraction(request -> """
                {"constraints":["一个晚上完成"],
                 "replacements":{"constraints":{"约两小时完成":"一个晚上完成"}},
                 "evidenceClaims":["README示例需要引用旧文案"]}
                """, new ObjectMapper());
        new ExploreUserProfileUseCase(repository, extraction).explore(profile.id(),
                "请在README示例里引用旧文案：一个晚上完成。不要改变我的时间约束。");
        var current = repository.findById(profile.id()).orElseThrow();
        assertEquals(List.of("约两小时完成"), current.constraints());
        assertEquals(corrected.revision(), current.revision());
        assertEquals(corrected.evidence(), current.evidence());
    }
}
