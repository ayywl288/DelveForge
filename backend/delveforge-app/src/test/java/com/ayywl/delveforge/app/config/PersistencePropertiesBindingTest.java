package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;
import com.ayywl.delveforge.infrastructure.persistence.PersistenceProperties;
import com.ayywl.delveforge.infrastructure.workspace.VerificationProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证配置绑定与默认值行为。
 *
 * <p>只加载 {@code application.yml} 与配置类本身，不启动 DataSource，
 * 因此不会创建任何真实数据库文件。
 */
class PersistencePropertiesBindingTest {

    /** 会读取 classpath 上的 application.yml。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @EnableConfigurationProperties(PersistenceProperties.class)
    static class PropertiesConfiguration {
    }

    @EnableConfigurationProperties({DeepSeekProperties.class, VerificationProperties.class})
    static class BudgetConfiguration {
    }

    @Test
    void bindsDefaultDatabaseFileFromApplicationYml() {
        runner.run(context -> assertEquals(
                // 两侧都 normalize：绑定结果可能已经被规范化，直接比较 Path 会因
                // "./" 这类冗余片段产生平台相关的差异。
                Path.of("./data/delveforge.db").normalize(),
                context.getBean(PersistenceProperties.class).databaseFile().normalize(),
                "默认数据库位置应来自 application.yml，而不是代码中的兜底值"));
    }

    @Test
    void defaultConfigurationRetainsReviewedLocalBudgets() {
        runner.withUserConfiguration(BudgetConfiguration.class).run(context -> {
            DeepSeekProperties ai = context.getBean(DeepSeekProperties.class);
            assertTrue(ai.streaming());
            assertEquals(Duration.ofSeconds(90), ai.timeout());
            assertEquals(Duration.ofSeconds(300), ai.streamTimeout());
            assertEquals(98304, ai.maxTokens());
            assertEquals(33554432, ai.maxResponseBytes());
            assertEquals(65536, ai.maxEventBytes());

            VerificationProperties verification = context.getBean(VerificationProperties.class);
            assertEquals(Duration.ofSeconds(900), verification.actionTimeout());
            assertEquals(Duration.ofSeconds(1200), verification.invocationTimeout());
            assertEquals(8388608, verification.maxOutputBytes());
            assertEquals(160, verification.maxTestReports());
            assertEquals(16, verification.maxActions());
            assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
        });
    }

    @Test
    void environmentOverrideWorksWithoutAProfile() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("databaseEnvironment", Map.of(
                                "DELVEFORGE_PERSISTENCE_DATABASE_FILE", "/custom/manual.db"))))
                .run(context -> assertEquals(
                        Path.of("/custom/manual.db"),
                        context.getBean(PersistenceProperties.class).databaseFile()));
    }

    @Test
    void commandLinePropertyOverridesTheEnvironmentPath() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("databaseEnvironment", Map.of(
                                "DELVEFORGE_PERSISTENCE_DATABASE_FILE", "/custom/manual.db"))))
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SimpleCommandLinePropertySource(
                                "--delveforge.persistence.database-file=/custom/explicit.db")))
                .run(context -> assertEquals(
                        Path.of("/custom/explicit.db"),
                        context.getBean(PersistenceProperties.class).databaseFile()));
    }

    @Test
    void bindsOverriddenDatabaseFile() {
        runner.withPropertyValues("delveforge.persistence.database-file=/custom/delveforge.db")
                .run(context -> assertEquals(
                        Path.of("/custom/delveforge.db"),
                        context.getBean(PersistenceProperties.class).databaseFile()));
    }

    /**
     * 配置缺失时必须快速失败，而不是静默使用一个意料之外的位置。
     *
     * <p>刻意不加载 application.yml，因此该属性确实不存在。
     */
    @Test
    void failsFastWhenDatabaseFileIsNotConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "配置缺失时上下文不应启动成功"));
    }
}
