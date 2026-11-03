package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.infrastructure.workspace.MavenRepositoryLayout;

import java.util.List;
import java.util.Map;

/** 与兼容性预检共享 reactor 与语言级别解析；显式选择仍需满足实际语言级别。 */
final class MavenJdkSelector {
    List<String> roots(Map<String, byte[]> poms) {
        try {
            return MavenRepositoryLayout.rootDirectories(poms);
        } catch (MavenRepositoryLayout.Unsupported failure) {
            throw new AgentException(failure.code(), failure.getMessage(), failure);
        }
    }

    int select(Map<String, byte[]> poms, Integer configured, AgentSandboxProperties properties) {
        return select(poms, configured, properties, null);
    }

    int select(Map<String, byte[]> poms, Integer configured, AgentSandboxProperties properties, String directory) {
        MavenRepositoryLayout layout;
        try {
            layout = MavenRepositoryLayout.inspect(poms, directory);
        } catch (MavenRepositoryLayout.Unsupported failure) {
            if (failure.code().equals("JDK_AMBIGUOUS")) {
                int chosen = configured == null ? properties.images().keySet().stream()
                        .filter(jdk -> jdk == 17 || jdk == 21).max(Integer::compareTo).orElse(properties.defaultJdk())
                        : configured;
                if (properties.images().containsKey(chosen)) {
                    // profile/父 POM 的生效属性由真实离线 Maven 判定；静态未知不是无效仓库。
                    return chosen;
                }
            }
            throw new AgentException(failure.code(), failure.getMessage());
        }
        int chosen = configured == null ? layout.recommendedJdk() : configured;
        if (configured == null && layout.languageLevel() != 17 && layout.languageLevel() != 21) {
            chosen = properties.defaultJdk();
        }
        if (configured == null && layout.modules().stream().map(module -> module.equals(".")
                ? "pom.xml" : module + "/pom.xml").map(poms::get).filter(java.util.Objects::nonNull)
                .anyMatch(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("<profiles>"))) {
            // 当前选择使用可用的最高受控 JDK 探测 profile，未声明生效模型已被静态解释。
            chosen = properties.images().keySet().stream().filter(jdk -> jdk == 17 || jdk == 21)
                    .max(Integer::compareTo).orElse(chosen);
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
