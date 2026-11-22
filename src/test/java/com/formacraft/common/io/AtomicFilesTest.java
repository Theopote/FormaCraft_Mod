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

    @Test
    void writeBytesReplacesWithoutLeavingTempFiles() throws IOException {
        Path target = tmp.resolve("thumb.png");
        Files.write(target, new byte[] {1});
        AtomicFiles.writeBytes(target, new byte[] {2, 3, 4});
        assertArrayEquals(new byte[] {2, 3, 4}, Files.readAllBytes(target));
        try (var files = Files.list(tmp)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void moveCreateNewPublishesDirectoryAndRefusesOverwrite() throws IOException {
        Path staging = Files.createTempDirectory(tmp, ".pending-");
        Files.writeString(staging.resolve("payload.txt"), "ready");
        Path destination = tmp.resolve("published");
        AtomicFiles.moveCreateNew(staging, destination);
        assertTrue(Files.isDirectory(destination));
        assertEquals("ready", Files.readString(destination.resolve("payload.txt")));
        assertFalse(Files.exists(staging));

        Path again = Files.createTempDirectory(tmp, ".pending-");
        Files.writeString(again.resolve("payload.txt"), "overwrite");
        assertThrows(IOException.class, () -> AtomicFiles.moveCreateNew(again, destination));
        assertEquals("ready", Files.readString(destination.resolve("payload.txt")));
        Files.delete(again.resolve("payload.txt"));
        Files.delete(again);
    }
}
