package com.ayywl.delveforge.infrastructure.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRepositoryIdentityAdapterTest {
    private final LocalRepositoryIdentityAdapter adapter = new LocalRepositoryIdentityAdapter();
    @TempDir Path temporary;

    @Test
    void windowsCaseSeparatorsDotsAndTrailingSlashHaveSameIdentity() {
        assertEquals(adapter.key("E:/task-d-nonexistent/demo", false),
                adapter.key("e:\\TASK-D-NONEXISTENT\\child\\..\\demo\\", false));
        assertEquals(adapter.key("\\\\server\\share\\demo", true),
                adapter.key("//SERVER/share/./demo/", true));
    }

    @Test
    void newRelativeAndDevicePathsAreRejected() {
        for (String path : new String[] {"relative/demo", "C:demo", "\\\\?\\C:\\demo", "\\\\.\\C:\\demo", "//server/share"}) {
            assertThrows(IllegalArgumentException.class, () -> adapter.key(path, false));
        }
    }

    @Test
    void realDirectoryAliasResolvesToSameIdentity() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path alias = temporary.resolve("alias");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    alias.toString(), target.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } else {
            Files.createSymbolicLink(alias, target);
        }
        try {
            assertEquals(adapter.key(target.toString(), false), adapter.key(alias.toString(), false));
            assertEquals(adapter.key(target.resolve("not-created-yet").toString(), false),
                    adapter.key(alias.resolve("not-created-yet").toString(), false));
        } finally {
            Files.delete(alias);
        }
    }
}
