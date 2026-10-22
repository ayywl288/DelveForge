package com.ayywl.delveforge.infrastructure.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Map;

@ConfigurationProperties("delveforge.agent.sandbox")
public record AgentSandboxProperties(Path root, String docker, Map<Integer, String> images, int defaultJdk,
        String owner, String cacheVolume, String memory, String cpus, int pids) {
    public AgentSandboxProperties {
        if (root == null || docker == null || docker.isBlank() || images == null || images.isEmpty()
                || !images.containsKey(defaultJdk) || owner == null || !owner.matches("[a-z0-9-]{4,60}")
                || cacheVolume == null || !cacheVolume.matches("[a-z0-9-]{4,100}")
                || memory == null || !memory.matches("[1-9][0-9]*[mg]")
                || cpus == null || !cpus.matches("[0-9]+(\\.[0-9]+)?") || Double.parseDouble(cpus) <= 0
                || pids < 16 || pids > 4096) {
            throw new IllegalArgumentException("Invalid Agent sandbox configuration");
        }
        images = Map.copyOf(images);
    }
}
