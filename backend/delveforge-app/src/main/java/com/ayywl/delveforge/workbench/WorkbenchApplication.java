package com.ayywl.delveforge.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;

/** 当前产品只装配项目对话工作台。已发布的 Step/Delivery 不扫描新数据库或副本。 */
@SpringBootApplication(exclude = FlywayAutoConfiguration.class)
public class WorkbenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkbenchApplication.class, args);
    }
}
