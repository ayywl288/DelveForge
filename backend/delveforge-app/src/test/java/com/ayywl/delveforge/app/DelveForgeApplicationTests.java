package com.ayywl.delveforge.app;

import com.ayywl.delveforge.app.error.ApiExceptionHandler;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 保证 Spring Boot 上下文可以完整装配并启动，包括 Persistence 与 Interface 层的横切设施。
 *
 * <p>使用测试专用的临时数据库路径，不接触开发者本地数据库。
 */
@SpringBootTest
class DelveForgeApplicationTests {

    /**
     * 使用独立的临时目录，避免在仓库工作目录下创建真实数据库。
     * 位于 target/ 下，属于构建产物，不会被提交。
     */
    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private ApiExceptionHandler apiExceptionHandler;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
        assertNotNull(dataSource, "SQLite DataSource 应完成装配");
        assertNotNull(sqlSessionFactory, "MyBatis-Plus SqlSessionFactory 应完成装配");
        // 统一错误映射必须被组件扫描发现，否则 @RestControllerAdvice 会静默失效。
        assertNotNull(apiExceptionHandler, "统一错误映射应完成装配");
    }

    /**
     * 默认日志级别不得让 DelveForge 自身代码输出 DEBUG/TRACE。
     *
     * <p>Mapper 接口位于 {@code com.ayywl.delveforge.infrastructure.persistence}，
     * 该包打开 DEBUG 会让 MyBatis 打印 SQL 与绑定参数值，其中可能包含用户数据。
     * 该断言防止有人为了排查问题整体调低级别后忘记恢复。
     */
    @Test
    void delveForgeLoggingIsNotVerboseByDefault() {
        assertConfigured("logging.level.root");
        assertConfigured("logging.level.com.ayywl.delveforge");
    }

    /**
     * 会输出未经筛选原始文本的框架包必须被显式固定。
     *
     * <p>{@code ExceptionHandlerExceptionResolver} 在 DEBUG 下输出
     * {@code Resolved [<异常.toString()>]}，这发生在 {@code @RestControllerAdvice} 之外，
     * 应用代码无法拦截；Tomcat / Coyote 在 DEBUG 下会输出请求头。
     * 如果这些包只是「没配」而继承 root，一旦 root 被调成 DEBUG 就会泄漏凭据。
     *
     * <p>因此这里要求它们必须被显式配置，而不只是「恰好没开 DEBUG」。
     */
    @Test
    void frameworkLoggersThatDumpRawExceptionsArePinned() {
        assertConfigured("logging.level.org.springframework.web");
        assertConfigured("logging.level.org.apache.tomcat");
        assertConfigured("logging.level.org.apache.coyote");
    }

    private void assertConfigured(String property) {
        String level = environment.getProperty(property);
        assertNotNull(level,
                property + " 必须显式配置，否则会继承 root 级别而在 DEBUG 下泄漏原始文本");
        assertFalse(Set.of("DEBUG", "TRACE").contains(level.toUpperCase()),
                property + " 不应为 " + level);
    }
}
