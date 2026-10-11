package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetAuthorizationMigrationTest {
    @TempDir Path temporary;

    @Test
    void upgradeFromV13PreservesLegacyDuplicatesAndAuthorizationWithoutGrantingAnything() throws Exception {
        String url = "jdbc:sqlite:" + temporary.resolve("upgrade.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("13").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO software_asset (id, type, source, location, read_permission, license_info, usage_authorization)
                    VALUES ('legacy-1', 'GIT_REPOSITORY', 'USER_SPECIFIED', 'E:/legacy/demo', 0, NULL, 'DENIED'),
                           ('legacy-2', 'GIT_REPOSITORY', 'USER_SPECIFIED', 'E:/legacy/demo', 1, NULL, 'UNCLEAR')
                    """);
        }
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT id, location, read_permission, license_info, usage_authorization, authorization_revision
                        FROM software_asset ORDER BY id
                        """)) {
            int count = 0;
            while (rows.next()) {
                assertEquals("legacy-" + ++count, rows.getString("id"));
                assertEquals("E:/legacy/demo", rows.getString("location"));
                assertEquals(count == 1 ? 0 : 1, rows.getInt("read_permission"));
                assertEquals(null, rows.getString("license_info"));
                assertEquals(count == 1 ? "DENIED" : "UNCLEAR", rows.getString("usage_authorization"));
                assertEquals(0, rows.getLong("authorization_revision"));
            }
            assertEquals(2, count);
        }
    }
}
