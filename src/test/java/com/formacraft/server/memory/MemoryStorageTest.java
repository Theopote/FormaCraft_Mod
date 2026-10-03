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
}
