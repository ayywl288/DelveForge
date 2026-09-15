package com.ayywl.delveforge.app.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus Mapper 扫描装配。
 *
 * <p>Mapper 接口属于 Infrastructure，不在 {@code @SpringBootApplication} 所在包之下，
 * MyBatis 的自动扫描不会覆盖它们，因此在 Composition Root 显式声明扫描范围。
 *
 * <p>本类只做装配，不承载任何持久化行为。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.ayywl.delveforge.infrastructure.persistence")
public class MyBatisMapperConfiguration {
}
