package com.formacraft.client.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackendAutoStarterLogRotateTest {

    @TempDir
    Path tmp;

    @Test
    void rotatesWhenOverMaxSize() throws Exception {
        File log = tmp.resolve("formacraft_backend_autostart.log").toFile();
        // Write just over the 8MB threshold without allocating a huge heap buffer twice.
        byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(log.toPath())) {
            for (int i = 0; i < 9; i++) {
                out.write(chunk);
            }
        }
        assertTrue(log.length() >= 8L * 1024 * 1024);

        BackendAutoStarter.rotateLogIfNeeded(log);

        File old = new File(log.getParentFile(), log.getName() + ".old");
        assertTrue(old.isFile(), "expected .old rotation");
        assertFalse(log.exists() && log.length() >= 8L * 1024 * 1024,
                "active log should be rotated away or truncated");
    }

    @Test
    void leavesSmallLogAlone() throws Exception {
        File log = tmp.resolve("small.log").toFile();
        Files.writeString(log.toPath(), "hello", StandardCharsets.UTF_8);
        BackendAutoStarter.rotateLogIfNeeded(log);
        assertEquals("hello", Files.readString(log.toPath()));
        assertFalse(new File(log.getParentFile(), "small.log.old").exists());
    }
}
