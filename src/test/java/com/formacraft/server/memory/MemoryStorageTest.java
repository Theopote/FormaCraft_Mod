package com.formacraft.server.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class MemoryStorageTest {
    @TempDir Path temporary;
    @Test void saveRootsNeverShareTheWorkingDirectoryMemory() throws IOException {
        Path first = MemoryStorage.directoryFor(temporary.resolve("world-a"));
        Path second = MemoryStorage.directoryFor(temporary.resolve("world-b"));
        Files.createDirectories(first); Files.createDirectories(second);
        MemoryStorage.writeAtomically(first.resolve("project.json"), "{\"world\":\"a\"}");
        assertFalse(Files.exists(second.resolve("project.json")));
        assertEquals(temporary.resolve("world-a/formacraft/memory"), first);
        assertNotEquals(first, second);
    }
    @Test void replacementWritesCompleteJsonAndCleansTemporaryFiles() throws IOException {
        var target = temporary.resolve("project.json");
        Files.writeString(target, "old"); MemoryStorage.writeAtomically(target, "new-json");
        assertEquals("new-json", Files.readString(target));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }
    @Test void failedReplacementCleansTemporaryFileAndLeavesTarget() throws IOException {
        var target = temporary.resolve("directory"); Files.createDirectory(target);
        Files.writeString(target.resolve("existing.json"), "preserved");
        assertThrows(IOException.class, () -> MemoryStorage.writeAtomically(target, "new-json"));
        assertEquals("preserved", Files.readString(target.resolve("existing.json")));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }

    @Test void sanitizeUuidLowercasesUpperHexDigits() {
        assertEquals(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                MemoryStorage.sanitizeUuid("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        );
    }

    @Test void readLimitedStringRejectsOversizedFiles() throws IOException {
        Path small = temporary.resolve("ok.json");
        Files.writeString(small, "x".repeat(64));
        Path big = temporary.resolve("too-big.json");
        try (var out = Files.newOutputStream(big)) {
            long remaining = MemoryStorage.MAX_MEMORY_FILE_BYTES + 1;
            byte[] chunk = new byte[1024 * 64];
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                out.write(chunk, 0, n);
                remaining -= n;
            }
        }
        IOException ex = assertThrows(IOException.class, () -> MemoryStorage.readLimitedString(big));
        assertTrue(ex.getMessage().contains("too large"));
        assertEquals("x".repeat(64), MemoryStorage.readLimitedString(small));
    }
}
