package com.ayywl.delveforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DelveForge 应用入口，同时作为 Composition Root。
 *
 * <p>显式扫描 {@code com.ayywl.delveforge}：Application 与 Infrastructure 位于启动类
 * 所在包之外，默认扫描范围不会覆盖它们。
 */
@SpringBootApplication(scanBasePackages = "com.ayywl.delveforge")
public class DelveForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DelveForgeApplication.class, args);
    }
}
