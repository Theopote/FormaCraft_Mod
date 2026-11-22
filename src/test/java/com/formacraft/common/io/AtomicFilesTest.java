package com.formacraft.common.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {

    @TempDir
    Path tmp;

    @Test
    void replacesWithoutLeavingTempFiles() throws IOException {
        Path target = tmp.resolve("doc.json");
        Files.writeString(target, "old");
        AtomicFiles.writeString(target, "{\"ok\":true}");
        assertEquals("{\"ok\":true}", Files.readString(target));
        try (var files = Files.list(tmp)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void failedReplaceLeavesExistingSiblingIntact() throws IOException {
        Path dirAsTarget = tmp.resolve("not-a-file");
        Files.createDirectory(dirAsTarget);
        Path sibling = dirAsTarget.resolve("kept.json");
        Files.writeString(sibling, "preserved");
        assertThrows(IOException.class, () -> AtomicFiles.writeString(dirAsTarget, "new"));
        assertEquals("preserved", Files.readString(sibling));
    }
}
