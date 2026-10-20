package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.infrastructure.workspace.WorkspaceProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 默认值测试不受用户启动终端的同名环境变量影响。
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @EnableConfigurationProperties(WorkspaceProperties.class)
    static class PropertiesConfiguration {
    }

    @Test
    void bindsDefaultWithoutCreatingDirectories() {
        runner.run(context -> {
            WorkspaceProperties properties = context.getBean(WorkspaceProperties.class);
            assertEquals(Path.of(System.getProperty("user.dir"), "data", "workspaces"), properties.root());
            assertTrue(properties.root().isAbsolute());
            assertEquals(List.of(), properties.existingRoots());
        });
    }

    @Test
    void historicalProfileIsNotPackaged() {
        assertNull(getClass().getResource("/application-m4-local.yml"));
    }

    @Test
    void environmentOverridesDefaultAndKeepsExplicitExistingRoots() {
        Path custom = Path.of("target", "storage-new").toAbsolutePath();
        Path old = Path.of("target", "storage-old").toAbsolutePath();
        runner.withPropertyValues("DELVEFORGE_WORKSPACE_ROOT=" + custom,
                "DELVEFORGE_WORKSPACE_EXISTING_ROOTS=" + old)
                .run(context -> {
                    WorkspaceProperties properties = context.getBean(WorkspaceProperties.class);
                    assertEquals(custom, properties.root());
                    assertEquals(List.of(old), properties.existingRoots());
                });
    }
}
