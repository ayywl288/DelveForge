package com.ayywl.delveforge.infrastructure.workspace;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "delveforge.workspace")
public record WorkspaceProperties(Path root, List<Path> existingRoots) {

    @ConstructorBinding
    public WorkspaceProperties {
        existingRoots = existingRoots == null ? List.of() : List.copyOf(existingRoots);
    }

    public WorkspaceProperties(Path root) {
        this(root, List.of());
    }
}
