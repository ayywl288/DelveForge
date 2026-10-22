package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInstanceLockTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsSameJvmAndIndependentJvmOwnersThenAllowsOwnershipAfterClose() throws Exception {
        Path database = temporary.resolve("state.db");
        try (var owner = new AgentInstanceLock(database)) {
            assertEquals("INSTANCE_CONFLICT", assertThrows(AgentException.class,
                    () -> new AgentInstanceLock(database)).code());
            // 同 JVM 的失败启动不能意外释放原生锁；另一个真实 JVM 仍须被拒绝。
            probe(database, false);
        }
        probe(database, true);
        try (var next = new AgentInstanceLock(database)) {
            probe(database, false);
        }
    }

    private void probe(Path database, boolean shouldAcquire) {
        var result = new AgentProcess().run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), OwnershipProbe.class.getName(), database.toString(),
                Boolean.toString(shouldAcquire)), null, null, Duration.ofSeconds(20), 8192, () -> false, Map.of());
        assertEquals(0, result.exitCode(), result.diagnostic());
        assertTrue(result.text().contains("INSTANCE_OWNERSHIP_PASS"));
    }

    public static final class OwnershipProbe {
        public static void main(String[] arguments) throws Exception {
            boolean acquired;
            try (var owner = new AgentInstanceLock(Path.of(arguments[0]))) {
                acquired = true;
            } catch (AgentException conflict) {
                if (!conflict.code().equals("INSTANCE_CONFLICT")) {
                    throw conflict;
                }
                acquired = false;
            }
            if (acquired != Boolean.parseBoolean(arguments[1])) {
                throw new AssertionError("Unexpected cross-process Agent ownership");
            }
            System.out.println("INSTANCE_OWNERSHIP_PASS");
        }
    }
}
