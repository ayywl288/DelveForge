package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.infrastructure.workspace.MavenRepositoryLayout;

import java.util.Map;

/** 与兼容性预检共享 reactor 与语言级别解析；显式选择仍需满足实际语言级别。 */
final class MavenJdkSelector {
    int select(Map<String, byte[]> poms, Integer configured, AgentSandboxProperties properties) {
        MavenRepositoryLayout layout;
        try {
            layout = MavenRepositoryLayout.inspect(poms);
        } catch (MavenRepositoryLayout.Unsupported failure) {
            throw new AgentException(failure.code(), failure.getMessage());
        }
        int chosen = configured == null ? layout.recommendedJdk() : configured;
        if (configured == null && layout.languageLevel() != 17 && layout.languageLevel() != 21) {
            chosen = properties.defaultJdk();
        }
        int required = Math.max(layout.languageLevel(), layout.minimumBuildJdk());
        if (configured == null && (!properties.images().containsKey(chosen) || chosen < required)) {
            chosen = properties.images().keySet().stream()
                    .filter(jdk -> (jdk == 17 || jdk == 21) && jdk >= required)
                    .sorted()
                    .findFirst()
                    .orElse(chosen);
        }
        if (!properties.images().containsKey(chosen) || chosen < required) {
            throw new AgentException("JDK_UNSUPPORTED", "所选受控 JDK 不满足 Maven 构建和语言级别要求。");
        }
        return chosen;
    }
}
