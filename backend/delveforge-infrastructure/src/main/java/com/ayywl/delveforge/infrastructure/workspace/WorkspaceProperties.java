package com.ayywl.delveforge.infrastructure.workspace;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "delveforge.workspace")
public record WorkspaceProperties(Path root) {}
